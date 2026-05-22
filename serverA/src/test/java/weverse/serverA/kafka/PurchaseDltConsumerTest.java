package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverA.repository.DeadLetterRepository;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PurchaseDltConsumerTest {

    @InjectMocks
    private PurchaseDltConsumer purchaseDltConsumer;

    @Mock
    private DeadLetterRepository deadLetterRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("DLT 메시지 수신 시 파싱에 성공하면 DeadLetter 테이블에 저장된다.")
    void consume_Dlt_Success() throws Exception {
        // Given
        String payload = "{\"traceId\":\"t-1\", \"goodsId\":2, \"quantity\":1}";
        String exceptionMsg = "DB Connection Timeout";

        // When
        purchaseDltConsumer.consume(payload, exceptionMsg);

        // Then
        verify(deadLetterRepository).save(argThat(dl ->
                "t-1".equals(dl.getTraceId()) &&
                        2L == dl.getGoodsId() &&
                        "DB Connection Timeout".equals(dl.getReason())
        ));
    }
}