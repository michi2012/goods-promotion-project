package weverse.serverA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.dto.StockSnapshotMessage;
import weverse.serverA.exception.DuplicateOrderException;
import weverse.serverA.exception.SoldOutException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        if (!redisStockService.reserveStock(message.goodsId(), message.quantity())) {
            redisStockService.releaseUserPurchase(message.userId(), message.goodsId());
            log.info("[품절] 재고 부족 | GoodsId: {}", message.goodsId());
            throw new SoldOutException();
        }
        publishStockSnapshot(message.goodsId());

        // 4. Kafka produce — 실패 시 Redis 롤백
        try {
            String payload = objectMapper.writeValueAsString(message);

            // whenComplete(비동기) 대신 get(동기 대기) 사용
            // 타임아웃(3초)을 주어 무한 대기를 방지합니다.
            kafkaTemplate.send(PURCHASE_TOPIC, String.valueOf(message.userId()), payload)
                         .get(3, TimeUnit.SECONDS);

            log.info("[구매 요청 접수 완료] TraceId: {}", message.orderId());

        } catch (TimeoutException e) {
            log.error("[Kafka produce 타임아웃] 브로커 응답 지연. Redis 롤백 진행 | TraceId: {}", message.orderId());
            rollbackRedis(message);
            throw new RuntimeException("주문 처리 지연. 다시 시도해주세요."); // 유저에게 에러 반환

        } catch (JsonProcessingException e) {
            log.error("[직렬화 실패] PurchaseMessage 변환 오류 | TraceId: {}", message.orderId(), e);
            rollbackRedis(message);
            throw new RuntimeException("PurchaseMessage 직렬화 실패", e);

        } catch (Exception e) {
            log.error("[Kafka produce 실패] 브로커 장애. Redis 롤백 진행 | TraceId: {} | 사유: {}", message.orderId(), e.getMessage());
            rollbackRedis(message);
            throw new RuntimeException("시스템 오류. 다시 시도해주세요.");
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