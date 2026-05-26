package weverse.serverC.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.service.PaymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentKafkaConsumerTest {

    @Mock
    private PaymentService paymentService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private Tracer tracer;

    @Mock
    private Propagator propagator;

    @InjectMocks
    private PaymentKafkaConsumer paymentKafkaConsumer;

    @Test
    @DisplayName("payment-request 메시지를 수신하면 파싱 후 PaymentService를 호출한다")
    void consume_Success() throws Exception {
        // given
        String payload = "{"
                + "\"orderId\":\"trace-1234\","
                + "\"userId\":1,"
                + "\"goodsId\":4,"
                + "\"quantity\":1,"
                + "\"paymentMethod\":\"CARD\","
                + "\"shippingAddress\":\"주소\","
                + "\"zipCode\":\"01234\","
                + "\"phoneNumber\":\"010-1234-5678\","
                + "\"email\":\"test@test.com\","
                + "\"deliveryMemo\":\"메모\","
                + "\"clientIp\":\"127.0.0.1\""
                + "}";
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-request", 0, 0L, null, payload);

        Span span = org.mockito.Mockito.mock(Span.class, org.mockito.Mockito.RETURNS_SELF);
        Tracer.SpanInScope spanInScope = org.mockito.Mockito.mock(Tracer.SpanInScope.class);

        doReturn(span).when(tracer).nextSpan();
        doReturn(spanInScope).when(tracer).withSpan(any(Span.class));

        // when
        paymentKafkaConsumer.consume(record);

        // then
        ArgumentCaptor<PurchaseMessage> captor = ArgumentCaptor.forClass(PurchaseMessage.class);
        verify(paymentService).processPayment(captor.capture());

        PurchaseMessage capturedMessage = captor.getValue();
        assertThat(capturedMessage.orderId()).isEqualTo("trace-1234");
        assertThat(capturedMessage.userId()).isEqualTo(1L);
        assertThat(capturedMessage.goodsId()).isEqualTo(4L);
    }
}
