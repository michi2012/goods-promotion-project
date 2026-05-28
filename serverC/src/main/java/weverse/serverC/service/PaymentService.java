package weverse.serverC.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverC.dto.PaymentResponse;
import weverse.serverC.dto.PaymentResultMessage;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.exception.PaymentNotFoundException;
import weverse.serverC.exception.PgPaymentException;
import weverse.serverC.outbox.OutboxEventService;
import weverse.serverC.repository.PaymentRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PgClient pgClient;
    private final OutboxEventService outboxEventService;
    private final MeterRegistry meterRegistry;

    private static final String RESULT_TOPIC = "payment-result";

    private Counter paymentAttempts;
    private Counter paymentSuccess;
    private Counter paymentErrorPgRejection;
    private Counter paymentErrorPgSystemError;

    @PostConstruct
    void initMetrics() {
        paymentAttempts = Counter.builder("business_payment_attempts_total")
                .description("총 결제 시도 횟수")
                .register(meterRegistry);
        paymentSuccess = Counter.builder("business_payment_success_total")
                .description("총 결제 성공 횟수")
                .register(meterRegistry);
        paymentErrorPgRejection = Counter.builder("business_payment_error_total")
                .description("PG 정상 응답 후 거절 (잔액부족, 카드한도 등)")
                .tag("type", "pg_rejection")
                .register(meterRegistry);
        paymentErrorPgSystemError = Counter.builder("business_payment_error_total")
                .description("PG 시스템 장애 (서킷브레이커 개방, 타임아웃 등)")
                .tag("type", "pg_system_error")
                .register(meterRegistry);
    }

    @Transactional
    public void processPayment(PurchaseMessage msg) {
        paymentAttempts.increment();
        paymentRepository.claimOrders(List.of(msg));

        List<String> failedTraceIds;
        try {
            failedTraceIds = pgClient.processPayments(List.of(msg));
        } catch (PgPaymentException e) {
            // 서킷브레이커 개방 또는 PG 시스템 무응답 — re-throw 금지: 트랜잭션 커밋으로 SAGA 보상 트리거 보장
            paymentErrorPgSystemError.increment();
            paymentRepository.updateOrderStatus(List.of(msg.orderId()), "FAILED");
            outboxEventService.save(msg.orderId(), RESULT_TOPIC,
                    new PaymentResultMessage(msg.orderId(), false, "PG 시스템 장애"));
            return;
        }

        boolean success = !failedTraceIds.contains(msg.orderId());
        paymentRepository.updateOrderStatus(List.of(msg.orderId()), success ? "PAID" : "FAILED");

        if (success) {
            paymentSuccess.increment();
        } else {
            // PG가 정상 응답했으나 거절 (잔액부족, 카드한도 등 비즈니스 거절)
            paymentErrorPgRejection.increment();
        }

        outboxEventService.save(msg.orderId(), RESULT_TOPIC,
                new PaymentResultMessage(msg.orderId(), success, success ? null : "결제 거절"));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getByUserId(Long userId, int page, int size) {
        return paymentRepository.findByUserId(userId, page, size);
    }
}
