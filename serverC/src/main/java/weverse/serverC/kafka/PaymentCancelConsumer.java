package weverse.serverC.kafka;

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
import org.springframework.stereotype.Component;
import weverse.serverC.dto.PaymentCancelMessage;
import weverse.serverC.service.PgClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCancelConsumer {

    private final PgClient pgClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;
    private final Propagator propagator;

    private Counter refundFatal;

    @PostConstruct
    void initMetrics() {
        refundFatal = Counter.builder("business_payment_pg_refund_fatal_total")
                .description("PG사 환불 요청까지 실패한 치명 오류 건수 (재시도 모두 소진)")
                .register(meterRegistry);
    }

    @KafkaListener(topics = "payment-cancel", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        PaymentCancelMessage msg = objectMapper.readValue(record.value(), PaymentCancelMessage.class);

        String traceparent = extractTraceparent(record);
        Span span = buildChildSpan(traceparent, "serverC.payment.cancel");
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            log.info("[PaymentCancel] payment-cancel 수신: orderId={}", msg.orderId());
            pgClient.cancelPayments(List.of(msg.orderId()));
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
        groupId = "${spring.kafka.consumer.group-id}-payment-cancel-dlt",
        containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void handleDlt(String payload) {
        refundFatal.increment();
        log.error("🚨 [DLT] payment-cancel 재시도 모두 소진 — 수동 정산 필요: payload={}", payload);
    }
}
