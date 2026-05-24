package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverA.service.OrderCommandService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PurchaseKafkaConsumerTest {

    @Mock
    private OrderCommandService orderCommandService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PurchaseKafkaConsumer purchaseKafkaConsumer;

    @Test
    @DisplayName("카프카 메시지를 수신하면 주문 저장을 수행한다 (Outbox 이벤트는 saveOrderAndDecreaseStock 내부 트랜잭션에서 처리)")
    void consume_Success() throws Exception {
        // given
        String payload = "{"
                + "\"traceId\":\"trace-123\","
                + "\"userId\":1,"
                + "\"goodsId\":4,"
                + "\"quantity\":2,"
                + "\"paymentMethod\":\"CARD\""
                + "}";

        // when
        purchaseKafkaConsumer.consume(payload);

        // then
        verify(orderCommandService).saveOrderAndDecreaseStock(any());
    }
}