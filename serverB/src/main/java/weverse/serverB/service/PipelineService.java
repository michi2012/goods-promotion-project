package weverse.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import weverse.serverB.dto.PurchaseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final Cache<Long, Boolean> soldOutCache = Caffeine.newBuilder()
                                                              .maximumSize(10000)
                                                              .expireAfterWrite(1, TimeUnit.HOURS)
                                                              .build();

    private final Cache<Long, Integer> stockCache = Caffeine.newBuilder()
                                                            .maximumSize(10000)
                                                            .expireAfterWrite(1, TimeUnit.SECONDS)
                                                            .build();

    public void processBulkData(List<PurchaseMessage> messages) {
        log.info("[PipelineService] 벌크 데이터 처리 시작: 수신된 메시지 수 = {}", messages.size());

        // 1. 멱등성 검증
        // 서버 A의 재시도로 인한 중복 메시지인지 MGET으로 한 번에 확인
        List<String> traceKeys = messages.stream().map(msg -> "trace:" + msg.traceId()).toList();
        List<String> existingTraces = redisTemplate.opsForValue().multiGet(traceKeys);

        List<PurchaseMessage> newMessages = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (existingTraces.get(i) == null) {
                newMessages.add(messages.get(i));
            } else {
                log.warn("🛡️ 중복 수신 방어 (멱등성 적용): TraceId {}", messages.get(i).traceId());
            }
        }

        if (newMessages.isEmpty()) {
            log.info("[PipelineService] 처리할 새로운 메시지가 없습니다.");
            return;
        }

        log.info("[PipelineService] 멱등성 검증 완료: 파이프라인으로 처리할 새 메시지 수 = {}", newMessages.size());

        // 2. 검증된 새로운 메시지만 파이프라인 처리
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {

                for (PurchaseMessage msg : newMessages) {
                    operations.opsForHash().put("user:" + msg.userId() + ":order", "status", "PROCESSING");
                    operations.opsForValue().set("trace:" + msg.traceId(), "OK", java.time.Duration.ofHours(1));

                    operations.opsForStream().add(
                            StreamRecords.newRecord()
                                         .in("queue:to_server_c")
                                         .ofMap(Map.of("payload", toJson(msg)))
                    );
                }
                return null;
            }
        });

        log.info("[PipelineService] 파이프라인 처리 및 서버 C 전송용 큐 적재 완료");
    }

    public String getUserOrderStatus(Long userId) {
        log.info("[PipelineService] 사용자 주문 상태 조회 요청: userId={}", userId);
        String userKey = "user:" + userId + ":order";

        // Redis Hash에서 'status' 필드의 값을 가져옴
        Object statusObj = redisTemplate.opsForHash().get(userKey, "status");

        if (statusObj != null) {
            log.info("[PipelineService] 주문 상태 조회 성공: userId={}, status={}", userId, statusObj);
            return statusObj.toString(); // 예: "PROCESSING", "SUCCESS", "FAIL"
        }

        log.info("[PipelineService] 주문 상태 찾을 수 없음 (미접수 또는 DB 이관됨): userId={}", userId);
        // Redis에 없으면 아직 접수가 안 됐거나, 너무 오래되어 DB로 넘어간 상태임
        return "NOT_FOUND";
    }

    public int getRemainingStock(Long goodsId) {
        log.info("[PipelineService] 상품 잔여 재고 조회 요청: goodsId={}", goodsId);

        if (isSoldOut(goodsId)) {
            log.info("[PipelineService] 상품 품절 상태로 확인됨: goodsId={}", goodsId);
            return 0;
        }

        // get(key, function)을 사용하면 Caffeine이 내부적으로 동기화 락을 겁니다.
        // 1만 명이 동시에 들어와도, 최초의 1명만 아래 람다식(Redis 조회)을 실행하고
        // 나머지 9,999명은 찰나의 대기 후 캐싱된 결과만 받아갑니다.
        Integer currentStock = stockCache.get(goodsId, key -> {
            log.info("[PipelineService] 로컬 캐시 미스 - Redis에서 재고 조회 진행: goodsId={}", key);
            String stockStr = redisTemplate.opsForValue().get("goods:" + key + ":stock");

            // 재고 정보가 없으면 함부로 0을 주지 않고 예외를 던짐 (또는 DB 조회를 시도)
            if (stockStr == null) {
                log.error("🚨 Redis에 재고 정보가 없습니다. Cache Pre-warming 상태를 확인하세요. 상품 ID: {}", key);
                throw new IllegalStateException("재고 정보를 불러올 수 없습니다.");
            }
            return Integer.parseInt(stockStr);
        });

        if (currentStock <= 0) {
            log.info("[PipelineService] 조회된 재고가 0 이하입니다. 품절 처리 진행: goodsId={}", goodsId);
            markGoodsAsSoldOut(goodsId);
            return 0;
        }

        log.info("[PipelineService] 상품 잔여 재고 반환: goodsId={}, currentStock={}", goodsId, currentStock);
        return currentStock;
    }

    public void markGoodsAsSoldOut(Long goodsId) {
        log.info("[PipelineService] 상품 품절 처리 시작: goodsId={}", goodsId);
        soldOutCache.put(goodsId, true);

        // 글로벌 품절 플래그에 24시간 TTL을 부여하여 Redis 메모리 보호
        redisTemplate.opsForValue().set("goods:" + goodsId + ":sold_out", "true", 24, TimeUnit.HOURS);
        log.warn("상품 [{}] 전역 품절 처리 완료 (TTL 24h 적용)", goodsId);
    }

    public boolean isSoldOut(Long goodsId) {
        Boolean isSoldOutLocal = soldOutCache.getIfPresent(goodsId);
        if (Boolean.TRUE.equals(isSoldOutLocal)) {
            log.info("[PipelineService] 로컬 캐시에서 품절 확인됨: goodsId={}", goodsId);
            return true;
        }

        String isSoldOutRedis = redisTemplate.opsForValue().get("goods:" + goodsId + ":sold_out");
        if ("true".equals(isSoldOutRedis)) {
            log.info("[PipelineService] Redis에서 품절 확인됨. 로컬 캐시 갱신: goodsId={}", goodsId);
            soldOutCache.put(goodsId, true);
            return true;
        }

        return false;
    }

    private String toJson(PurchaseMessage msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("[PipelineService] JSON 직렬화 실패: traceId={}", msg.traceId(), e);
            throw new RuntimeException("JSON Serialize Error", e);
        }
    }
}