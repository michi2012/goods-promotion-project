package weverse.serverC.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverC.dto.PaymentResultMessage;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.outbox.OutboxEventService;
import weverse.serverC.repository.FinalOrderRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final FinalOrderRepository finalOrderRepository;
    private final PgClient pgClient;
    private final OutboxEventService outboxEventService;
    private final MeterRegistry meterRegistry;

    private static final String RESULT_TOPIC = "payment-result";

    private Counter paymentAttempts;
    private Counter paymentSuccess;
    private Counter paymentError;

    @PostConstruct
    void initMetrics() {
        paymentAttempts = Counter.builder("business_payment_attempts_total")
                .description("총 결제 시도 횟수")
                .register(meterRegistry);
        paymentSuccess = Counter.builder("business_payment_success_total")
                .description("총 결제 성공 횟수")
                .register(meterRegistry);
        paymentError = Counter.builder("business_payment_error_total")
                .description("총 결제 실패 횟수")
                .register(meterRegistry);
    }

    @Transactional
    public void processPayment(PurchaseMessage msg) {
        paymentAttempts.increment();
        finalOrderRepository.claimOrders(List.of(msg));

        List<String> failedTraceIds = pgClient.processPayments(List.of(msg));
        boolean success = !failedTraceIds.contains(msg.orderId());

        String status = success ? "PAID" : "FAILED";
        finalOrderRepository.updateOrderStatus(List.of(msg.orderId()), status);

        if (success) {
            paymentSuccess.increment();
        } else {
            paymentError.increment();
        }

        // 결과 이벤트 저장 — 동일 트랜잭션 내 Outbox 저장
        String errorMsg = success ? null : "결제 거절";
        outboxEventService.save(msg.orderId(), RESULT_TOPIC,
                new PaymentResultMessage(msg.orderId(), success, errorMsg));
    }
}
