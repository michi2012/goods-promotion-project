package promotion.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.service.dlt.DeadLetterService;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PurchaseDltConsumerTest {

    @InjectMocks
    private PurchaseDltConsumer purchaseDltConsumer;

    @Mock
    private DeadLetterService deadLetterService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("DLT 메시지 수신 시 파싱에 성공하면 DeadLetterService.saveDeadLetter를 호출한다.")
    void consume_Dlt_Success() throws Exception {
        // Given
        String payload = "{\"orderId\":\"t-1\", \"goodsId\":2, \"quantity\":1}";
        String exceptionMsg = "DB Connection Timeout";

        // When
        purchaseDltConsumer.consume(payload, exceptionMsg);

        // Then
        verify(deadLetterService).saveDeadLetter("t-1", 2L, 1, "DB Connection Timeout");
    }

    @Test
    @DisplayName("DLT 메시지 파싱 실패 시에도 UNKNOWN orderId로 saveDeadLetter를 호출한다.")
    void consume_Dlt_ParseFail() throws Exception {
        // Given
        String invalidPayload = "invalid-json";
        String exceptionMsg = "Some error";

        // When
        purchaseDltConsumer.consume(invalidPayload, exceptionMsg);

        // Then
        verify(deadLetterService).saveDeadLetter("UNKNOWN", null, 0, "Some error");
    }
}