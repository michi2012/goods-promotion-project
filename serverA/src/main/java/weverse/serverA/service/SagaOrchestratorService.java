package weverse.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.OrderCompletedMessage;
import weverse.serverA.dto.OrderStatusMessage;
import weverse.serverA.dto.PaymentCancelMessage;
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
    private static final String PAYMENT_CANCEL_TOPIC = "payment-cancel";

    @Transactional
    public void tryCompleteSaga(String orderId) {
        SagaStateData state = sagaStateService.getSagaState(orderId);
        if (state == null) {
            log.warn("[Saga] SagaState 없음 (이미 완료/삭제됨): orderId={}", orderId);
            return;
        }
        if (sagaStateService.isFailed(orderId)) {
            log.warn("[Saga] 이미 실패 처리된 Saga: orderId={}", orderId);
            return;
        }

        // 조건부 업데이트: PENDING 상태일 때만 PAID로 변경 (멱등성)
        int updated = orderRepository.updateStatusIfPending(orderId, OrderStatus.PAID);
        if (updated == 0) {
            log.warn("[Saga] Order 상태 변경 실패 (이미 처리됨): orderId={}", orderId);
            sagaStateService.deleteSagaState(orderId);
            return;
        }

        // 서버B: PAID 상태 알림, 서버C: 최종 저장 명령 — 동일 트랜잭션 내 Outbox 저장
        outboxEventService.save(orderId, ORDER_STATUS_TOPIC,
                new OrderStatusMessage(orderId, state.userId(), "PAID"));
        outboxEventService.save(orderId, ORDER_COMPLETED_TOPIC, new OrderCompletedMessage(orderId));

        sagaStateService.deleteSagaState(orderId);
        log.info("[Saga 최종 확정] PAID | orderId={}", orderId);
    }

    @Transactional
    public void handleSagaFailure(String orderId, String reason) {
        // 멱등성: 이미 failed 처리 중이면 무시
        if (!sagaStateService.markFailedAndCheck(orderId)) {
            log.warn("[Saga] 중복 실패 처리 무시: orderId={}", orderId);
            return;
        }

        SagaStateData state = sagaStateService.getSagaState(orderId);
        if (state == null) {
            log.warn("[Saga] SagaState 없음, 보상 건너뜀: orderId={}", orderId);
            return;
        }

        // 결제가 이미 성공한 경우, 취소(망취소) 이벤트를 먼저 확인 — SagaState 삭제 전에 읽어야 함
        boolean paymentWasCompleted = sagaStateService.isPaymentCompleted(orderId);

        OrderStatus failStatus = "TIMEOUT".equals(reason) ? OrderStatus.EXPIRED : OrderStatus.FAILED;
        orderRepository.updateStatusIfPending(orderId, failStatus);

        // 보상: Redis 재고 복구 + 유저 마킹 해제
        redisStockService.releaseStock(state.goodsId(), state.quantity());
        redisStockService.releaseUserPurchase(state.userId(), state.goodsId());

        // 보상: DB 재고 복구
        goodsRepository.increaseStockAtomically(state.goodsId(), state.quantity());

        // 보상: PG 결제가 성공했던 경우 취소 요청 — 동일 트랜잭션 내 Outbox 저장
        if (paymentWasCompleted) {
            outboxEventService.save(orderId, PAYMENT_CANCEL_TOPIC, new PaymentCancelMessage(orderId));
            log.info("[Saga 보상] PG 결제 취소 이벤트 발행: orderId={}", orderId);
        }

        // 재고 스냅샷 + 서버B 실패 상태 알림 — 동일 트랜잭션 내 Outbox 저장
        Long remaining = redisStockService.getCurrentStock(state.goodsId());
        outboxEventService.save(String.valueOf(state.goodsId()), STOCK_SNAPSHOT_TOPIC,
                new StockSnapshotMessage(state.goodsId(), remaining));
        outboxEventService.save(orderId, ORDER_STATUS_TOPIC,
                new OrderStatusMessage(orderId, state.userId(), failStatus.name()));

        sagaStateService.deleteSagaState(orderId);
        log.info("[Saga 보상 완료] {} | 사유: {} | orderId={}", failStatus, reason, orderId);
    }
}
