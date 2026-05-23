package weverse.serverA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.OrderCompletedMessage;
import weverse.serverA.dto.OrderStatusMessage;
import weverse.serverA.dto.SagaStateData;
import weverse.serverA.dto.StockSnapshotMessage;
import weverse.serverA.entity.OrderStatus;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OrderRepository;
import weverse.serverA.service.RedisStockService;
import weverse.serverA.service.SagaStateService;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestratorService {

    private final SagaStateService sagaStateService;
    private final OrderRepository orderRepository;
    private final GoodsRepository goodsRepository;
    private final RedisStockService redisStockService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String ORDER_STATUS_TOPIC = "order-status-update";
    private static final String ORDER_COMPLETED_TOPIC = "order-completed";
    private static final String STOCK_SNAPSHOT_TOPIC = "stock-snapshot";

    @Transactional
    public void tryCompleteSaga(String traceId) {
        SagaStateData state = sagaStateService.getSagaState(traceId);
        if (state == null) {
            log.warn("[Saga] SagaState 없음 (이미 완료/삭제됨): traceId={}", traceId);
            return;
        }
        if (sagaStateService.isFailed(traceId)) {
            log.warn("[Saga] 이미 실패 처리된 Saga: traceId={}", traceId);
            return;
        }

        // 조건부 업데이트: PENDING 상태일 때만 PAID로 변경 (멱등성)
        int updated = orderRepository.updateStatusIfPending(traceId, OrderStatus.PAID);
        if (updated == 0) {
            log.warn("[Saga] Order 상태 변경 실패 (이미 처리됨): traceId={}", traceId);
            sagaStateService.deleteSagaState(traceId);
            return;
        }

        // 서버B: PAID 상태 알림, 서버C: 최종 저장 명령
        sendOrderStatusUpdate(traceId, state.userId(), "PAID");
        sendOrderCompleted(traceId);

        sagaStateService.deleteSagaState(traceId);
        log.info("[Saga 최종 확정] PAID | traceId={}", traceId);
    }

    @Transactional
    public void handleSagaFailure(String traceId, String reason) {
        // 멱등성: 이미 failed 처리 중이면 무시
        if (!sagaStateService.markFailedAndCheck(traceId)) {
            log.warn("[Saga] 중복 실패 처리 무시: traceId={}", traceId);
            return;
        }

        SagaStateData state = sagaStateService.getSagaState(traceId);
        if (state == null) {
            log.warn("[Saga] SagaState 없음, 보상 건너뜀: traceId={}", traceId);
            return;
        }

        OrderStatus failStatus = "TIMEOUT".equals(reason) ? OrderStatus.EXPIRED : OrderStatus.FAILED;
        orderRepository.updateStatusIfPending(traceId, failStatus);

        // 보상: Redis 재고 복구 + 유저 마킹 해제
        redisStockService.releaseStock(state.goodsId(), state.quantity());
        redisStockService.releaseUserPurchase(state.userId(), state.goodsId());
        sendStockSnapshot(state.goodsId());

        // 보상: DB 재고 복구
        goodsRepository.increaseStockAtomically(state.goodsId(), state.quantity());

        // 서버B: 실패 상태 알림
        sendOrderStatusUpdate(traceId, state.userId(), failStatus.name());

        sagaStateService.deleteSagaState(traceId);
        log.info("[Saga 보상 완료] {} | 사유: {} | traceId={}", failStatus, reason, traceId);
    }

    private void sendOrderStatusUpdate(String traceId, Long userId, String status) {
        try {
            String msg = objectMapper.writeValueAsString(new OrderStatusMessage(traceId, userId, status));
            kafkaTemplate.send(ORDER_STATUS_TOPIC, traceId, msg);
        } catch (JsonProcessingException e) {
            log.error("[Saga] order-status-update produce 실패: traceId={}", traceId, e);
        }
    }

    private void sendOrderCompleted(String traceId) {
        try {
            String msg = objectMapper.writeValueAsString(new OrderCompletedMessage(traceId));
            kafkaTemplate.send(ORDER_COMPLETED_TOPIC, traceId, msg);
        } catch (JsonProcessingException e) {
            log.error("[Saga] order-completed produce 실패: traceId={}", traceId, e);
        }
    }

    private void sendStockSnapshot(Long goodsId) {
        try {
            Long remaining = redisStockService.getCurrentStock(goodsId);
            String msg = objectMapper.writeValueAsString(new StockSnapshotMessage(goodsId, remaining));
            kafkaTemplate.send(STOCK_SNAPSHOT_TOPIC, String.valueOf(goodsId), msg);
        } catch (Exception e) {
            log.warn("[Saga] stock-snapshot produce 실패 (무시): goodsId={}, error={}", goodsId, e.getMessage());
        }
    }
}
