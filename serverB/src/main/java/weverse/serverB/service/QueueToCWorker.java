package weverse.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import weverse.serverB.client.ExternalApiClient;
import weverse.serverB.dto.PurchaseMessage;
import weverse.serverB.dto.ServerCResponse;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueueToCWorker {
    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;
    private final ExternalApiClient externalApiClient;
    private final ObjectMapper objectMapper;

    @Value("${external.server-c.url}")
    private String serverCUrl;

    @Value("${external.server-a.url}")
    private String serverAUrl;

    private static final String STREAM_KEY = "queue:to_server_c";
    private static final String DLQ_KEY = "queue:dead_letters";
    private static final String COMPENSATE_RETRY_KEY = "queue:retry_compensate_to_a";
    private static final String GROUP_NAME = "server-b-group";

    // UUID 대신 고정된 컨슈머 이름 사용
    // 서버가 재시작되어도 '내가 처리하다 만 Pending 메시지'를 다시 찾을 수 있습니다.
    private final String consumerName = "server-b-worker-1";

    @PostConstruct
    public void init() {
        log.info("[QueueToCWorker] Redis Stream Consumer Group 생성 시도: {}", GROUP_NAME);
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), GROUP_NAME);
            log.info("[QueueToCWorker] Redis Stream Consumer Group 생성 완료: {}", GROUP_NAME);
        } catch (Exception e) {
            log.info("Redis Stream Consumer Group 이미 존재함: {}", GROUP_NAME);
        }
    }

    // [스케줄러 1] 실시간 신규 메시지 처리 (100ms 간격)
    @Scheduled(fixedDelay = 100)
    public void dispatchToServerC() {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(GROUP_NAME, consumerName),
                StreamReadOptions.empty().count(500),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
        );

        if (records != null && !records.isEmpty()) {
            log.info("[QueueToCWorker] 신규 메시지 {}건 수신 및 처리 시작", records.size());
            processRecords(records);
        }
    }

    // [스케줄러 2] 미처리(Pending) 고아 메시지 복구 (5초 간격)
    @Scheduled(fixedDelay = 5000)
    public void recoverPendingMessages() {
        // ReadOffset.from("0") = 내가 예전에 읽어놓고 ACK를 못한 메시지 조회
        List<MapRecord<String, Object, Object>> pendingRecords = redisTemplate.opsForStream().read(
                Consumer.from(GROUP_NAME, consumerName),
                StreamReadOptions.empty().count(100),
                StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
        );

        if (pendingRecords != null && !pendingRecords.isEmpty()) {
            log.warn("🔄 [장애 복구] ACK 대기 중인 Pending 메시지 {}건 재처리 시도", pendingRecords.size());
            processRecords(pendingRecords);
        }
    }

    private void processRecords(List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) return;

        log.info("[QueueToCWorker] Server C로 전송할 페이로드 변환 작업 시작: 대상 건수 = {}", records.size());
        List<PurchaseMessage> payloads = new ArrayList<>();
        List<RecordId> recordIds = new ArrayList<>();

        for (MapRecord<String, Object, Object> record : records) {
            try {
                String json = (String) record.getValue().get("payload");
                payloads.add(objectMapper.readValue(json, PurchaseMessage.class));
                recordIds.add(record.getId());
            } catch (Exception e) {
                log.error("❌ JSON 파싱 실패. 데이터를 DLQ로 이동시킵니다.");
                moveToDlq(record);
            }
        }

        if (payloads.isEmpty()) {
            log.info("[QueueToCWorker] 처리할 유효한 페이로드가 없습니다.");
            return;
        }

        try {
            log.info("[QueueToCWorker] Server C Bulk API 호출 시도: 유효 데이터 건수 = {}", payloads.size());
            ResponseEntity<ServerCResponse> response = externalApiClient.sendBulkToServerC(
                    serverCUrl + "/api/v1/c/orders/bulk", payloads);

            // 확실한 응답(2xx)을 받았을 때만 비로소 ACK 수행
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                ServerCResponse result = response.getBody();

                if (result.hasFailures()) {
                    log.info("[QueueToCWorker] Server C 처리 중 일부 실패 발생. 성공/실패 분리 처리 시작");
                    List<PurchaseMessage> failedMsgs = payloads.stream()
                                                               .filter(p -> result.getFailedTraceIds().contains(p.traceId())).toList();
                    List<PurchaseMessage> successMsgs = payloads.stream()
                                                                .filter(p -> !result.getFailedTraceIds().contains(p.traceId())).toList();

                    updateUserStatus(successMsgs, "SUCCESS");
                    updateUserStatus(failedMsgs, "FAIL");
                    triggerCompensationToServerA(failedMsgs);
                } else {
                    log.info("[QueueToCWorker] Server C 전체 처리 성공");
                    updateUserStatus(payloads, "SUCCESS");
                }

                // 모든 처리가 완벽히 끝난 후 최후에 ACK 날림
                acknowledgeAndDelete(recordIds);
            }

        } catch (HttpClientErrorException.BadRequest e) {
            log.error("⚠️ 서버 C 400 Bad Request 수신: 데이터 규격 위반. 전체 DLQ 이관 및 알람 발생");
            moveToDlqBatch(records);
            updateUserStatus(payloads, "FAIL");
            acknowledgeAndDelete(recordIds); // DLQ 이관 완료 후 ACK

        } catch (Exception e) {
            // 일시적 장애. 여기서 예외가 터지면 ACK가 호출되지 않음
            // 따라서 다음번 recoverPendingMessages() 스케줄러가 알아서 재처리함.
            log.error("🚨 서버 C 일시적 장애 ({}). ACK 보류 후 재전송 대기", e.getMessage());
        }
    }


    // [스케줄러 3] 끝까지 쫓아가는 좀비 워커 (보상 트랜잭션 재시도)
    @Scheduled(fixedDelay = 3000)
    public void retryFailedCompensations() {
        String json = redisTemplate.opsForList().leftPop(COMPENSATE_RETRY_KEY);
        if (json == null) return;

        log.info("[QueueToCWorker] 재시도 큐에서 보상 트랜잭션 요청 수신, 재처리 시도");
        weverse.serverB.dto.CompensationRequest req = null;
        try {
            req = objectMapper.readValue(json, weverse.serverB.dto.CompensationRequest.class);
            externalApiClient.sendCompensationToServerA(serverAUrl + "/api/v1/internal/compensate", List.of(req));
            log.info("⏪ [재시도 성공] 서버 A로 재고 롤백 지시 완료. TraceId: {}", req.traceId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("🚨 [재시도 영구 실패] 보상 데이터 JSON 파싱 실패. 폐기 처리합니다: {}", json);

        } catch (Exception e) {
            log.warn("🚨 [재시도 실패] 서버 A가 아직 복구되지 않았습니다. 큐에 다시 넣습니다. TraceId: {}",
                    (req != null ? req.traceId() : "Unknown"));
            redisTemplate.opsForList().rightPush(COMPENSATE_RETRY_KEY, json);
        }
    }

    private void updateUserStatus(List<PurchaseMessage> payloads, String statusValue) {
        log.info("[QueueToCWorker] Redis 사용자 주문 상태 업데이트 ({}): 대상 건수 = {}", statusValue, payloads.size());
        redisTemplate.executePipelined(new RedisCallback<Object>() {
            @Override
            public Object doInRedis(RedisConnection connection) {
                var serializer = redisTemplate.getStringSerializer();
                byte[] field = serializer.serialize("status");
                byte[] val = serializer.serialize(statusValue);
                for (PurchaseMessage msg : payloads) {
                    connection.hashCommands().hSet(serializer.serialize("user:" + msg.userId() + ":order"), field, val);
                }
                return null;
            }
        });
    }

    private void acknowledgeAndDelete(List<RecordId> recordIds) {
        log.info("[QueueToCWorker] Redis Stream 메시지 ACK 및 삭제 완료: 처리 건수 = {}", recordIds.size());
        RecordId[] idsArray = recordIds.toArray(new RecordId[0]);
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, idsArray);
        redisTemplate.opsForStream().delete(STREAM_KEY, idsArray);
    }

    private void triggerCompensationToServerA(List<PurchaseMessage> payloads) {
        log.info("[QueueToCWorker] 실패한 요청에 대한 보상 트랜잭션(서버 A) 지시 시작: 건수 = {}", payloads.size());
        List<weverse.serverB.dto.CompensationRequest> compRequests = payloads.stream()
                                                                             .map(msg -> new weverse.serverB.dto.CompensationRequest(
                                                                                     msg.traceId(), msg.goodsId(), msg.quantity(), "PG 결제 거절 (서버 C 요청)"
                                                                             )).toList();

        try {
            externalApiClient.sendCompensationToServerA(serverAUrl + "/api/v1/internal/compensate", compRequests);            log.info("⏪ 서버 A로 재고 롤백(보상 트랜잭션) 지시 성공");
        } catch (Exception e) {
            log.error("🚨 서버 A 통신 실패! 최종 일관성 보장을 위해 재시도 큐(Redis)에 적재합니다.", e);
            fallbackToRedisRetryQueue(compRequests);
        }
    }

    private void moveToDlq(MapRecord<String, Object, Object> record) {
        log.info("[QueueToCWorker] 메시지를 DLQ로 이동: RecordId = {}", record.getId());
        redisTemplate.opsForStream().add(StreamRecords.newRecord().in(DLQ_KEY).ofObject(record.getValue()));
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
        redisTemplate.opsForStream().delete(STREAM_KEY, record.getId());
    }

    private void moveToDlqBatch(List<MapRecord<String, Object, Object>> records) {
        log.info("[QueueToCWorker] 메시지 배치를 DLQ로 이동 시작: 대상 건수 = {}", records.size());
        records.forEach(this::moveToDlq);
    }

    private void fallbackToRedisRetryQueue(List<weverse.serverB.dto.CompensationRequest> compRequests) {
        log.info("[QueueToCWorker] 보상 트랜잭션 실패로 인한 재시도 큐 적재 시작: 대상 건수 = {}", compRequests.size());
        try {
            for (var req : compRequests) {
                String json = objectMapper.writeValueAsString(req);
                redisTemplate.opsForList().rightPush(COMPENSATE_RETRY_KEY, json);
            }
            log.info("[QueueToCWorker] 재시도 큐 적재 완료");
        } catch (Exception e) {
            log.error("🚨 치명적 에러: Redis 재시도 큐 적재조차 실패했습니다. (수동 복구 필요)", e);
        }
    }
}