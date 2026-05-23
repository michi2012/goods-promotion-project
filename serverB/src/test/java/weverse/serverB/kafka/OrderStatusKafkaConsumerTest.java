package weverse.serverB.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverB.dto.OrderStatusMessage;
import weverse.serverB.service.OrderStatusEventHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderStatusKafkaConsumerTest {

    @Mock
    private OrderStatusEventHandler orderStatusEventHandler;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderStatusKafkaConsumer orderStatusKafkaConsumer;

    @Test
    @DisplayName("카프카 메시지를 수신하면 역직렬화 후 이벤트 핸들러로 전달한다")
    void consume_Success() throws Exception {
        // given
        String payload = "{"
                + "\"traceId\":\"trace-123\","
                + "\"userId\":1,"
                + "\"status\":\"PENDING\""
                + "}";

        // when
        orderStatusKafkaConsumer.consume(payload);

        // then
        ArgumentCaptor<OrderStatusMessage> captor = ArgumentCaptor.forClass(OrderStatusMessage.class);
        verify(orderStatusEventHandler).handleStatusUpdate(captor.capture());

        OrderStatusMessage capturedMessage = captor.getValue();
        assertThat(capturedMessage.traceId()).isEqualTo("trace-123");
        assertThat(capturedMessage.userId()).isEqualTo(1L);
        assertThat(capturedMessage.status()).isEqualTo("PENDING");
    }
}