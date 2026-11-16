package promotion.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import promotion.serverA.client.UserQueryClient;
import promotion.serverA.dto.OrderStatusMessage;
import promotion.serverA.dto.PaymentCancelMessage;
import promotion.serverA.dto.StockSnapshotMessage;
import promotion.serverA.dto.response.RefundResponse;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;
import promotion.serverA.exception.RefundException;
import promotion.serverA.outbox.OutboxEventService;
import promotion.serverA.repository.GoodsRepository;
import promotion.serverA.repository.OrderRepository;

import java.time.LocalDateTime;

/**
 * 사용자 환불 요청. 정책·응답은 docs/specs/user-refund.md (P-2·P-3·P-6, S-3~S-6).
 * user-service 조회(외부 호출)는 트랜잭션 밖에서 하고, 검사·상태 전이·Outbox만 트랜잭션으로 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final String PAYMENT_CANCEL_TOPIC = "payment-cancel";
    private static final String ORDER_STATUS_TOPIC = "order-status-update";
    private static final String STOCK_SNAPSHOT_TOPIC = "stock-snapshot";

    private final UserQueryClient userQueryClient;
    private final OrderRepository orderRepository;
    private final GoodsRepository goodsRepository;
    private final RedisStockService redisStockService;
    private final OutboxEventService outboxEventService;
    private final TransactionTemplate transactionTemplate;

    @Value("${refund.period-days:7}")
    private long periodDays;

    public RefundResponse requestRefund(String orderId, String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw RefundException.missingUser();
        }
        Long userId = resolveUserId(loginId);

        RefundResponse result = transactionTemplate.execute(tx -> requestInTransaction(orderId, userId));
        if (result != null) {
            return result;
        }
        // 동시 요청에서 조건부 UPDATE를 진 경우. 같은 트랜잭션에서 다시 읽으면 MySQL REPEATABLE READ 스냅샷이라
        // 처음 읽은 PAID가 보이므로, 새 트랜잭션에서 이긴 쪽이 커밋한 현재 상태를 읽는다 (S-6).
        return transactionTemplate.execute(tx -> currentRefundStatus(orderId));
    }

    private Long resolveUserId(String loginId) {
        try {
            return userQueryClient.findUserId(loginId).orElseThrow(RefundException::orderNotFound);
        } catch (RefundException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[Refund] user-service 조회 실패: error={}", e.getMessage());
            throw RefundException.userLookupUnavailable();
        }
    }

    // 접수했으면 응답을, 동시 요청에 져서 판단을 미뤄야 하면 null을 돌려준다.
    private RefundResponse requestInTransaction(String orderId, Long userId) {
        Order order = orderRepository.findByOrderId(orderId)
                .filter(o -> userId.equals(o.getUserId()))
                .orElseThrow(RefundException::orderNotFound);

        OrderStatus status = order.getStatus();
        if (status == OrderStatus.REFUND_REQUESTED || status == OrderStatus.REFUNDED) {
            return new RefundResponse(orderId, status.name()); // 멱등 (P-6)
        }
        if (status != OrderStatus.PAID) {
            throw RefundException.notAllowedStatus();
        }
        if (order.getPaidAt().plusDays(periodDays).isBefore(LocalDateTime.now())) {
            throw RefundException.periodExpired(periodDays);
        }

        if (orderRepository.updateStatusIfCurrent(orderId, OrderStatus.PAID, OrderStatus.REFUND_REQUESTED) == 0) {
            return null;
        }
        outboxEventService.save(orderId, PAYMENT_CANCEL_TOPIC, new PaymentCancelMessage(orderId));
        outboxEventService.save(orderId, ORDER_STATUS_TOPIC,
                new OrderStatusMessage(orderId, order.getUserId(), OrderStatus.REFUND_REQUESTED.name()));
        log.info("[Refund] 환불 접수: orderId={}", orderId);
        return new RefundResponse(orderId, OrderStatus.REFUND_REQUESTED.name());
    }

    /**
     * PG 취소 확정(payment-cancelled) 처리. 환불 접수(REFUND_REQUESTED) 주문만 REFUNDED로 바꾸고 재고를 되돌린다 (P-5).
     * 망취소(FAILED/EXPIRED)·중복 수신은 조건부 전이가 0행이라 아무것도 하지 않는다. 구매 표시는 풀지 않는다 (P-12).
     */
    public void completeRefund(String orderId) {
        transactionTemplate.executeWithoutResult(tx -> {
            if (orderRepository.updateStatusIfCurrent(orderId, OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED) == 0) {
                log.info("[Refund] 환불 접수 주문이 아니라 완료 처리 생략 (망취소·중복): orderId={}", orderId);
                return;
            }
            Order order = orderRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException("REFUNDED로 바꾼 주문이 없습니다: " + orderId));

            // Saga 보상과 같은 방식: 같은 트랜잭션에서 DB 재고 + Redis 재고 + 스냅샷·상태 Outbox
            goodsRepository.increaseStockAtomically(order.getGoodsId(), order.getQuantity());
            redisStockService.releaseStock(order.getGoodsId(), order.getQuantity());
            Long remaining = redisStockService.getCurrentStock(order.getGoodsId());
            outboxEventService.save(String.valueOf(order.getGoodsId()), STOCK_SNAPSHOT_TOPIC,
                    new StockSnapshotMessage(order.getGoodsId(), remaining));
            outboxEventService.save(orderId, ORDER_STATUS_TOPIC,
                    new OrderStatusMessage(orderId, order.getUserId(), OrderStatus.REFUNDED.name()));
            log.info("[Refund] 환불 완료 + 재고 복구: orderId={}, goodsId={}, quantity={}",
                    orderId, order.getGoodsId(), order.getQuantity());
        });
    }

    private RefundResponse currentRefundStatus(String orderId) {
        OrderStatus status = orderRepository.findByOrderId(orderId)
                .map(Order::getStatus)
                .orElseThrow(RefundException::orderNotFound);
        if (status == OrderStatus.REFUND_REQUESTED || status == OrderStatus.REFUNDED) {
            return new RefundResponse(orderId, status.name());
        }
        throw RefundException.notAllowedStatus();
    }
}
