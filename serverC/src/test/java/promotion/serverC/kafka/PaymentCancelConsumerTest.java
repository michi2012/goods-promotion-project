package promotion.serverC.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;
import promotion.serverC.exception.PgPaymentException;
import promotion.serverC.service.PaymentCancelService;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentCancelConsumerTest {

    @Mock
    private PaymentCancelService paymentCancelService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    @InjectMocks
    private PaymentCancelConsumer consumer;

    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("payment-cancel", 0, 0L, "order-1", "{\"orderId\":\"order-1\"}");

    @BeforeEach
    void setUpTracing() {
        Span span = mock(Span.class, org.mockito.Mockito.RETURNS_SELF);
        lenient().doReturn(span).when(tracer).nextSpan();
        lenient().doReturn(mock(Tracer.SpanInScope.class)).when(tracer).withSpan(any(Span.class));
    }

    @Test
    @DisplayName("payment-cancel을 받으면 orderId로 취소 서비스를 호출한다")
    void consumeDelegatesToService() throws Exception {
        consumer.consume(record);

        verify(paymentCancelService).cancel("order-1");
    }

    @Test
    @DisplayName("DLT에 도착하면 예외 메시지 헤더와 함께 자동 재시도 대기로 기록한다")
    void handleDltRecordsFailure() throws Exception {
        ConsumerRecord<String, String> dlt =
                new ConsumerRecord<>("payment-cancel.DLT", 0, 0L, "order-1", "{\"orderId\":\"order-1\"}");
        dlt.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "PG 장애".getBytes(StandardCharsets.UTF_8));
        consumer.initMetrics();

        consumer.handleDlt(dlt);

        verify(paymentCancelService).recordDltArrival("order-1", "PG 장애");
    }

    @Test
    @DisplayName("취소 서비스가 실패하면 예외를 다시 던져 Kafka 재시도에 맡긴다")
    void consumeRethrowsFailure() {
        doThrow(new PgPaymentException("PG 장애")).when(paymentCancelService).cancel("order-1");

        assertThatThrownBy(() -> consumer.consume(record)).isInstanceOf(PgPaymentException.class);
    }
}
