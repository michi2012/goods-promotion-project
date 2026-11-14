package promotion.serverA.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    // 종료 주문 재대사 상한. 이보다 오래된 CDC 장애는 장애 대응 영역으로 본다.
    private static final long TERMINATED_MAX_AGE_HOURS = 24L;
    private static final List<OrderStatus> TERMINATED_STATUSES = List.of(OrderStatus.EXPIRED, OrderStatus.FAILED);

    // 정상적인 늦은 결과(늦은 결제 성공 보상)가 먼저 처리되도록 두는 하한. E2E에서 짧게 주입한다.
    @Value("${saga.reconcile.terminated.min-age-minutes:10}")
    private long terminatedMinAgeMinutes;

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

    // 주문은 EXPIRED/FAILED로 끝났는데 serverC 결제는 PAID로 남은 경우를 찾는다.
    // 늦은 payment-result(success)나 payment-cancel이 CDC에서 유실되면 이벤트 기반 보상이 실행되지 않으므로 직접 조회한다.
    // serverC가 망취소를 마치면 CANCELLED → NOT_PAID로 조회되어 다음 주기부터 자연히 대상에서 빠진다.
    @Scheduled(fixedDelayString = "${saga.reconcile.terminated.interval-ms:600000}")
    public void reconcileTerminatedOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> terminatedOrders = orderRepository.findByStatusInAndCreatedAtBetween(
                TERMINATED_STATUSES, now.minusHours(TERMINATED_MAX_AGE_HOURS), now.minusMinutes(terminatedMinAgeMinutes));

        for (Order order : terminatedOrders) {
            String orderId = order.getOrderId();
            switch (paymentQueryClient.checkPaymentStatus(orderId)) {
                case PAID -> sagaOrchestratorService.compensateLostPaymentResult(orderId);
                case NOT_PAID -> { }
                case UNKNOWN -> log.warn("[OrderReconciliation] 종료 주문 결제 상태 확인 불가, 다음 주기 재시도: orderId={}", orderId);
            }
        }
    }
}
