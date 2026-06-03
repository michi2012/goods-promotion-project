package promotion.serverA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import promotion.serverA.dto.PurchaseMessage;
import promotion.serverA.dto.StockSnapshotMessage;
import promotion.serverA.exception.DuplicateOrderException;
import promotion.serverA.exception.SoldOutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    private final RedisStockService redisStockService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String PURCHASE_TOPIC = "purchase_events";
    private static final String STOCK_SNAPSHOT_TOPIC = "stock-snapshot";

    public void acceptPurchase(PurchaseMessage message) {
        // 1. 품절 캐시 선 차단 (Redis 왕복 없이 즉시 거절)
        if (redisStockService.isKnownSoldOut(message.goodsId())) {
            throw new SoldOutException();
        }

        // 2. 중복 구매 방어 (Redis SETNX)
        if (!redisStockService.tryMarkUserPurchased(message.userId(), message.goodsId())) {
            log.info("[중복 구매 차단] UserId: {} | GoodsId: {}", message.userId(), message.goodsId());
            throw new DuplicateOrderException();
        }

        // 3. 재고 선점 (Redis Lua)
        Long stockResult = redisStockService.reserveStock(message.goodsId(), message.quantity());
        if (stockResult == null || stockResult == -2L) {
            redisStockService.releaseUserPurchase(message.userId(), message.goodsId());
            log.error("[재고 미초기화] Redis 키 없음 | GoodsId: {}", message.goodsId());
            throw new RuntimeException("재고 정보 미초기화: goodsId=" + message.goodsId());
        }
        if (stockResult == -1L) {
            redisStockService.releaseUserPurchase(message.userId(), message.goodsId());
            log.debug("[품절] 재고 부족 | GoodsId: {}", message.goodsId());
            throw new SoldOutException();
        }
        publishStockSnapshot(message.goodsId());

        // 4. Kafka produce — 비동기 발행, 실패 시 콜백에서 Redis 롤백
        try {
            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(PURCHASE_TOPIC, String.valueOf(message.userId()), payload)
                         .whenComplete((result, ex) -> {
                             if (ex != null) {
                                 log.error("[Kafka produce 실패] Redis 롤백 진행 | TraceId: {} | 사유: {}", message.orderId(), ex.getMessage());
                                 try {
                                     rollbackRedis(message);
                                 } catch (Exception redisEx) {
                                     log.error("[롤백 실패 — 수동 복구 필요] orderId: {} | goodsId: {} | userId: {} | qty: {} | 사유: {}",
                                             message.orderId(), message.goodsId(), message.userId(), message.quantity(), redisEx.getMessage());
                                 }
                             } else {
                                 log.debug("[구매 요청 접수 완료] TraceId: {}", message.orderId());
                             }
                         });
        } catch (JsonProcessingException e) {
            log.error("[직렬화 실패] PurchaseMessage 변환 오류 | TraceId: {}", message.orderId(), e);
            rollbackRedis(message);
            throw new RuntimeException("PurchaseMessage 직렬화 실패", e);
        }
    }

    private void rollbackRedis(PurchaseMessage message) {
        redisStockService.releaseStock(message.goodsId(), message.quantity());
        redisStockService.releaseUserPurchase(message.userId(), message.goodsId());
    }

    private void publishStockSnapshot(Long goodsId) {
        try {
            Long remaining = redisStockService.getCurrentStock(goodsId);
            String payload = objectMapper.writeValueAsString(new StockSnapshotMessage(goodsId, remaining));
            kafkaTemplate.send(STOCK_SNAPSHOT_TOPIC, String.valueOf(goodsId), payload);
        } catch (Exception e) {
            log.warn("[stock-snapshot] produce 실패 (무시): goodsId={}, error={}", goodsId, e.getMessage());
        }
    }

}