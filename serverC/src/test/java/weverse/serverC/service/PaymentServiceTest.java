package weverse.serverC.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.repository.FinalOrderRepository;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private FinalOrderRepository finalOrderRepository;

    @Mock
    private PgClient pgClient;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("결제 성공 시 PAID 상태로 업데이트하고 성공 이벤트를 발행한다")
    void processPayment_Success() throws Exception {
        // given
        String traceId = "trace-1234";
        PurchaseMessage message = new PurchaseMessage(
                traceId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );

        // PG 클라이언트가 실패한 traceId 리스트로 빈 리스트를 반환한다고 가정 (결제 성공)
        when(pgClient.processPayments(anyList())).thenReturn(Collections.emptyList());

        // when
        paymentService.processPayment(message);

        // then
        verify(finalOrderRepository).claimOrders(anyList());
        verify(finalOrderRepository).updateOrderStatus(List.of(traceId), "PAID");

        // JSON 결과에 success:true 가 포함되어 발행되는지 검증
        verify(kafkaTemplate).send(eq("payment-result"), eq(traceId), contains("\"success\":true"));
    }

    @Test
    @DisplayName("결제 실패 시 FAILED 상태로 업데이트하고 실패 이벤트를 발행한다")
    void processPayment_Fail() throws Exception {
        // given
        String traceId = "trace-1234";
        PurchaseMessage message = new PurchaseMessage(
                traceId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );

        // PG 클라이언트가 실패한 traceId 로 현재 traceId를 반환한다고 가정 (결제 실패)
        when(pgClient.processPayments(anyList())).thenReturn(List.of(traceId));

        // when
        paymentService.processPayment(message);

        // then
        verify(finalOrderRepository).claimOrders(anyList());
        verify(finalOrderRepository).updateOrderStatus(List.of(traceId), "FAILED");

        // JSON 결과에 success:false 가 포함되어 발행되는지 검증
        verify(kafkaTemplate).send(eq("payment-result"), eq(traceId), contains("\"success\":false"));
    }
}