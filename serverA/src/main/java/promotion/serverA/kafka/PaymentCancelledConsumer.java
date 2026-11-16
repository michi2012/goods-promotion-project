package promotion.serverA.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import promotion.serverA.dto.PaymentCancelledMessage;
import promotion.serverA.service.RefundService;

// serverC의 PG 취소 확정(payment-cancelled)을 받아 환불을 완료한다 (docs/specs/user-refund.md "이벤트").
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelledConsumer {

    private final RefundService refundService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private Counter completeFailed;

    @PostConstruct
    void initMetrics() {
        completeFailed = Counter.builder("business_refund_complete_failed_total")
                .description("payment-cancelled 처리(환불 완료·재고 복구)가 Kafka 재시도를 모두 소진한 건수 — 주문이 REFUND_REQUESTED에 남아 관리자 확인 필요")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "payment-cancelled", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String payload) throws JsonProcessingException {
        PaymentCancelledMessage msg = objectMapper.readValue(payload, PaymentCancelledMessage.class);
        log.info("[Refund] payment-cancelled 수신: orderId={}", msg.orderId());
        refundService.completeRefund(msg.orderId());
    }

    // DLT는 기록만 한다 — 예외를 던지면 DLT 오프셋이 막힌다. 주문은 REFUND_REQUESTED("환불 처리 중")로 남는다.
    @KafkaListener(
            topics = "payment-cancelled.DLT",
            groupId = "${spring.kafka.consumer.group-id}.dlt",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void handleDlt(
            @Payload String payload,
            @Header(value = "kafka_dlt_exception_message", required = false) String exceptionMessage
    ) {
        completeFailed.increment();
        log.error("🚨 [DLT] payment-cancelled 처리 실패 — 환불 완료·재고 복구 안 됨, 관리자 확인 필요. error={}, payload={}",
                exceptionMessage, payload);
    }
}
