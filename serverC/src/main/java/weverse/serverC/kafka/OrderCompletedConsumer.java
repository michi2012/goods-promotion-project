package weverse.serverC.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverC.dto.OrderCompletedMessage;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCompletedConsumer {

    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;

    @KafkaListener(topics = "order-completed", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        OrderCompletedMessage msg = objectMapper.readValue(record.value(), OrderCompletedMessage.class);

        String traceparent = extractTraceparent(record);
        Span span = buildChildSpan(traceparent, "serverC.order.completed");
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            log.info("[OrderCompleted] Saga 최종 확정 수신: orderId={}", msg.orderId());
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
}
