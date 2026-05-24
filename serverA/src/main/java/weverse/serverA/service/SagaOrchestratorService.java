package weverse.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.OrderCompletedMessage;
import weverse.serverA.dto.OrderStatusMessage;
import weverse.serverA.dto.SagaStateData;
import weverse.serverA.dto.StockSnapshotMessage;
import weverse.serverA.entity.OrderStatus;
import weverse.serverA.outbox.OutboxEventService;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OrderRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestratorService {

    private final SagaStateService sagaStateService;
    private final OrderRepository orderRepository;
    private final GoodsRepository goodsRepository;
    private final RedisStockService redisStockService;
    private final OutboxEventService outboxEventService;

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

        // 서버B: PAID 상태 알림, 서버C: 최종 저장 명령 — 동일 트랜잭션 내 Outbox 저장
        outboxEventService.save(traceId, ORDER_STATUS_TOPIC,
                new OrderStatusMessage(traceId, state.userId(), "PAID"));
        outboxEventService.save(traceId, ORDER_COMPLETED_TOPIC, new OrderCompletedMessage(traceId));

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

        // 보상: DB 재고 복구
        goodsRepository.increaseStockAtomically(state.goodsId(), state.quantity());

        // 재고 스냅샷 + 서버B 실패 상태 알림 — 동일 트랜잭션 내 Outbox 저장
        Long remaining = redisStockService.getCurrentStock(state.goodsId());
        outboxEventService.save(String.valueOf(state.goodsId()), STOCK_SNAPSHOT_TOPIC,
                new StockSnapshotMessage(state.goodsId(), remaining));
        outboxEventService.save(traceId, ORDER_STATUS_TOPIC,
                new OrderStatusMessage(traceId, state.userId(), failStatus.name()));

        sagaStateService.deleteSagaState(traceId);
        log.info("[Saga 보상 완료] {} | 사유: {} | traceId={}", failStatus, reason, traceId);
    }
}
