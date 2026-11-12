package promotion.serverA.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import promotion.serverA.client.PaymentCheckStatus;
import promotion.serverA.client.PaymentQueryClient;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;
import promotion.serverA.repository.OrderRepository;
import promotion.serverA.service.SagaOrchestratorService;

import java.time.LocalDateTime;
import java.util.List;

// SagaTimeoutScheduler는 Redis saga:state:* 키 존재를 전제로 스캔한다.
// Redis 상태가 유실(재시작/eviction)되면 그 스캐너로는 영원히 못 찾으므로,
// DB orders 테이블을 직접 스캔해 방치된 PENDING 주문을 찾아 보정하는 안전망.
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderReconciliationScheduler {

    private final OrderRepository orderRepository;
    private final PaymentQueryClient paymentQueryClient;
    private final SagaOrchestratorService sagaOrchestratorService;

    private static final long STALE_THRESHOLD_MINUTES = 5L;

    @Scheduled(fixedDelay = 2 * 60_000)
    public void reconcilePendingOrders() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
        List<Order> staleOrders = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, threshold);
        if (staleOrders.isEmpty()) {
            return;
        }

        log.warn("[OrderReconciliation] Redis Saga 상태 유실 의심 PENDING 주문 {}건 탐지", staleOrders.size());
        for (Order order : staleOrders) {
            String orderId = order.getOrderId();
            PaymentCheckStatus status = paymentQueryClient.checkPaymentStatus(orderId);
            switch (status) {
                case PAID -> sagaOrchestratorService.reconcileToPaid(orderId);
                case NOT_PAID -> sagaOrchestratorService.reconcileToFailed(orderId);
                case UNKNOWN -> log.warn("[OrderReconciliation] 결제 상태 확인 불가, 다음 주기 재시도: orderId={}", orderId);
            }
        }
    }
}
