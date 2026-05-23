package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.Order;
import weverse.serverA.entity.OrderStatus;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaProduceServiceTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private KafkaProduceService kafkaProduceService;

    @Test
    @DisplayName("상태 업데이트와 결제 요청 카프카 이벤트를 각각 발행한다")
    void publishNextEvents_Success() throws Exception {
        // given
        PurchaseMessage message = new PurchaseMessage(
                "trace-123", 1L, 4L, 2, "CARD", "주소", "12345", "010-0000-0000", "test@test.com", "메모", "127.0.0.1"
        );

        Order order = Order.builder()
                           .traceId("trace-123")
                           .status(OrderStatus.PENDING)
                           .build();

        ReflectionTestUtils.setField(order, "id", 100L);

        // when
        kafkaProduceService.publishNextEvents(message, order);

        // then
        verify(kafkaTemplate).send(eq("order-status-update"), eq("trace-123"), contains("\"status\":\"PENDING\""));
        verify(kafkaTemplate).send(eq("payment-request"), eq("trace-123"), contains("\"goodsId\":4"));
    }
}