package weverse.serverC.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentKafkaConsumerTest {

    @Mock
    private PaymentService paymentService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentKafkaConsumer paymentKafkaConsumer;

    @Test
    @DisplayName("payment-request 메시지를 수신하면 파싱 후 PaymentService를 호출한다")
    void consume_Success() throws Exception {
        // given
        String payload = "{"
                + "\"traceId\":\"trace-1234\","
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

        // when
        paymentKafkaConsumer.consume(payload);

        // then
        ArgumentCaptor<PurchaseMessage> captor = ArgumentCaptor.forClass(PurchaseMessage.class);
        verify(paymentService).processPayment(captor.capture());

        PurchaseMessage capturedMessage = captor.getValue();
        assertThat(capturedMessage.traceId()).isEqualTo("trace-1234");
        assertThat(capturedMessage.userId()).isEqualTo(1L);
        assertThat(capturedMessage.goodsId()).isEqualTo(4L);
    }
}