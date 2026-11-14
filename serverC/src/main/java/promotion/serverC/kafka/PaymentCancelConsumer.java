package promotion.serverC.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import promotion.serverC.dto.PaymentCancelMessage;
import promotion.serverC.service.PaymentCancelService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelConsumer {

    private final PaymentCancelService paymentCancelService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    private Counter refundFatal;

    @PostConstruct
    void initMetrics() {
        refundFatal = Counter.builder("business_payment_pg_refund_fatal_total")
                .description("PG 취소가 Kafka 재시도를 모두 소진해 자동 재시도 대기로 넘어간 건수 (관리자 처리 필요는 business_payment_cancel_retry_exhausted_total)")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "payment-cancel", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        PaymentCancelMessage msg = objectMapper.readValue(record.value(), PaymentCancelMessage.class);

        String traceparent = extractTraceparent(record);
        Span span = buildChildSpan(traceparent, "serverC.payment.cancel");
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            log.info("[PaymentCancel] payment-cancel 수신: orderId={}", msg.orderId());
            paymentCancelService.cancel(msg.orderId());
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private String extractTraceparent(ConsumerRecord<?, ?> record) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader("traceparent");
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }

    private Span buildChildSpan(String traceparent, String spanName) {
        if (traceparent == null) {
            return tracer.nextSpan().name(spanName).start();
        }
        return propagator.extract(Map.of("traceparent", traceparent), Map::get).name(spanName).start();
    }

    @KafkaListener(
        topics = "payment-cancel.DLT",
        groupId = "${spring.kafka.consumer.group-id}.dlt",
        containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void handleDlt(ConsumerRecord<String, String> record) throws Exception {
        refundFatal.increment();
        PaymentCancelMessage msg = objectMapper.readValue(record.value(), PaymentCancelMessage.class);
        String error = headerValue(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
        log.error("🚨 [DLT] payment-cancel Kafka 재시도 소진 → 자동 재시도 대기로 기록: orderId={}, error={}",
                msg.orderId(), error);
        paymentCancelService.recordDltArrival(msg.orderId(), error);
    }

    private String headerValue(ConsumerRecord<?, ?> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
