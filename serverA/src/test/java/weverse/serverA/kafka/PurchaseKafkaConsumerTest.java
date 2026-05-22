package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OutboxRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PurchaseKafkaConsumerTest {

    @InjectMocks
    private PurchaseKafkaConsumer purchaseKafkaConsumer;

    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private GoodsRepository goodsRepository;
    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Kafka 메시지를 수신하면 Outbox 저장과 재고 차감이 정상적으로 수행된다.")
    void consume_Success() throws Exception {
        // Given
        String payload = "{\"traceId\":\"t-1\", \"userId\":1, \"goodsId\":2, \"quantity\":1}";
        PurchaseMessage message = new PurchaseMessage("t-1", 1L, 2L, 1, "C", "A", "Z", "P", "E", "M", "I");

        given(objectMapper.readValue(payload, PurchaseMessage.class)).willReturn(message);

        // When
        purchaseKafkaConsumer.consume(payload);

        // Then
        verify(outboxRepository, times(1)).save(any(RequestOutbox.class));
        verify(goodsRepository, times(1)).decreaseStock(message.goodsId(), message.quantity());
    }
}