package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import weverse.serverA.entity.Order;
import weverse.serverA.entity.OrderStatus;
import weverse.serverA.service.OrderCommandService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseKafkaConsumerTest {

    @Mock
    private OrderCommandService orderCommandService;

    @Mock
    private KafkaProduceService kafkaProduceService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PurchaseKafkaConsumer purchaseKafkaConsumer;

    @Test
    @DisplayName("카프카 메시지를 수신하면 DB 저장을 먼저 수행하고, 완료 후 카프카 이벤트를 발행한다")
    void consume_Success() throws Exception {
        // given
        String payload = "{"
                + "\"traceId\":\"trace-123\","
                + "\"userId\":1,"
                + "\"goodsId\":4,"
                + "\"quantity\":2,"
                + "\"paymentMethod\":\"CARD\""
                + "}";

        Order mockOrder = Order.builder()
                               .traceId("trace-123")
                               .status(OrderStatus.PENDING)
                               .build();

        ReflectionTestUtils.setField(mockOrder, "id", 100L);

        when(orderCommandService.saveOrderAndDecreaseStock(any())).thenReturn(mockOrder);

        // when
        purchaseKafkaConsumer.consume(payload);

        // then
        // 두 서비스가 반드시 '순서대로' 호출되어야 함을 검증
        InOrder inOrder = inOrder(orderCommandService, kafkaProduceService);
        inOrder.verify(orderCommandService).saveOrderAndDecreaseStock(any());
        inOrder.verify(kafkaProduceService).publishNextEvents(any(), eq(mockOrder));
    }
}