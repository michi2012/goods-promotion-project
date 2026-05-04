package weverse.serverA.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.OutboxRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushScheduler {

    @Value("${external.server-b.url}")
    private String serverBUrl;

    private volatile boolean isBackpressureActive = false;
    private volatile long backpressureEndTime = 0;
    private static final String PURCHASE_BULK_API = "/api/v1/b/purchases/bulk";

    private final RestTemplate restTemplate;
    private final OutboxRepository outboxRepository;
    private final OutboxClaimer outboxClaimer;

    @Scheduled(fixedDelay = 100)
    public void pushToServerB() {
        // 배압(Backpressure) 유지 시간 체크
        if (isBackpressureActive && System.currentTimeMillis() < backpressureEndTime) {
            return;
        }
        isBackpressureActive = false;

        // 짧은 트랜잭션으로 PUBLISHING 변경 후 가져옴
        List<RequestOutbox> publishingList = outboxClaimer.claimSuccessRecords();
        if (publishingList.isEmpty()) return;

        // 빠른 ID 추출 (성공/실패 벌크 업데이트용)
        List<Long> targetIds = publishingList.stream().map(RequestOutbox::getId).toList();

        if (targetIds.isEmpty()) return;

        try {
            List<PurchaseMessage> payloads = publishingList.stream()
                                                           .map(outbox -> new PurchaseMessage(
                                                                   outbox.getTraceId(),
                                                                   outbox.getUserId(),
                                                                   outbox.getGoodsId(),
                                                                   outbox.getQuantity(),
                                                                   outbox.getPaymentMethod(),
                                                                   outbox.getShippingAddress(),
                                                                   outbox.getZipCode(),
                                                                   outbox.getPhoneNumber(),
                                                                   outbox.getEmail(),
                                                                   outbox.getDeliveryMemo(),
                                                                   outbox.getClientIp()
                                                           )).collect(Collectors.toList());

            String url = serverBUrl + PURCHASE_BULK_API;
            ResponseEntity<String> response = restTemplate.postForEntity(url, payloads, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                outboxRepository.updateStatusByIds(OutboxStatus.SENT, targetIds);
            }

        } catch (HttpClientErrorException.BadRequest e) {
            // 400 Bad Request 수신 시 영구 FAIL 처리하여 좀비 데이터 방지
            log.error("🚨 서버 B 400 Bad Request. 불량 데이터이므로 FAIL 처리 후 폐기");
            outboxRepository.updateStatusByIds(OutboxStatus.FAIL, targetIds);
        } catch (HttpClientErrorException.TooManyRequests e) {
            applyBackpressureAndRollback("Server B 429 수신", targetIds);
        } catch (Exception e) {
            applyBackpressureAndRollback("Server B 타임아웃/연결 실패", targetIds);
        }
    }

    /**
     * 배압을 활성화하고 PUBLISHING 상태의 데이터를 다시 SUCCESS로 롤백시키는 공통 메서드
     */
    private void applyBackpressureAndRollback(String reason, List<Long> targetIds) {
        log.error("{} - 3초간 Push를 중단하고 Backoff를 수행합니다.", reason);
        isBackpressureActive = true;
        backpressureEndTime = System.currentTimeMillis() + 3000;

        // 단 1번의 쿼리로 SUCCESS 롤백
        outboxRepository.updateStatusByIds(OutboxStatus.SUCCESS, targetIds);
    }
}