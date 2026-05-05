package weverse.serverC.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.dto.ServerCResponse;
import weverse.serverC.repository.FinalOrderRepository;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderProcessingServiceTest {

    @InjectMocks private OrderProcessingService orderProcessingService;
    @Mock private FinalOrderRepository finalOrderRepository;
    @Mock private PgClient pgClient;
    @Mock private TransactionTemplate transactionTemplate;

    private List<PurchaseMessage> messages;

    @BeforeEach
    void setUp() {
        PurchaseMessage msg1 = new PurchaseMessage("trace-1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP");
        PurchaseMessage msg2 = new PurchaseMessage("trace-2", 2L, 1L, 2, "CARD", "ADDR", "123", "010", "B", "M", "IP");
        messages = List.of(msg1, msg2);

        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    @DisplayName("성공: 중복 없는 새로운 결제 건들이 들어오면 PG 승인 후 DB에 SUCCESS로 업데이트한다.")
    void processBulkOrders_Success() {
        // Given
        given(finalOrderRepository.claimOrders(anyList())).willReturn(messages); // 모두 내가 선점함
        given(pgClient.processPayments(anyList())).willReturn(List.of()); // 실패 건수 0 (모두 성공)

        // When
        ServerCResponse response = orderProcessingService.processBulkOrders(messages);

        // Then
        assertThat(response.hasFailures()).isFalse();
        verify(finalOrderRepository).updateOrderStatus(List.of("trace-1", "trace-2"), "SUCCESS");
        verify(pgClient, never()).cancelPayments(anyList()); // 환불 호출 안 됨
    }

    @Test
    @DisplayName("부분 실패: PG 통신 중 일부 잔액 부족이 발생하면, 성공/실패를 나누어 DB에 기록한다.")
    void processBulkOrders_PartialFailure() {
        // Given
        given(finalOrderRepository.claimOrders(anyList())).willReturn(messages);
        given(pgClient.processPayments(anyList())).willReturn(List.of("trace-2")); // trace-2만 결제 실패

        // When
        ServerCResponse response = orderProcessingService.processBulkOrders(messages);

        // Then
        assertThat(response.hasFailures()).isTrue();
        assertThat(response.getFailedTraceIds()).containsExactly("trace-2");

        verify(finalOrderRepository).updateOrderStatus(List.of("trace-1"), "SUCCESS"); // 1번은 성공
        verify(finalOrderRepository).updateOrderStatus(List.of("trace-2"), "FAIL");    // 2번은 실패
    }

    @Test
    @DisplayName("치명적 장애: PG 승인 후 DB 업데이트 중 DB가 뻗으면(Exception), 즉시 PG 강제 취소를 요청한다.")
    void processBulkOrders_DbFails_TriggersPgCancellation() {
        // Given
        given(finalOrderRepository.claimOrders(anyList())).willReturn(messages);
        given(pgClient.processPayments(anyList())).willReturn(List.of()); // PG 승인은 완료됨

        // DB 트랜잭션 실행 중 예외(DB 다운) 발생 설정
        doThrow(new RuntimeException("DB Connection Timeout!"))
                .when(transactionTemplate).executeWithoutResult(any());

        // When
        ServerCResponse response = orderProcessingService.processBulkOrders(messages);

        // Then
        // 1. 환불(망취소) API가 즉시 호출되어야 함
        verify(pgClient).cancelPayments(List.of("trace-1", "trace-2"));

        // 2. 서버 B에게는 전부 실패했다고 알려주어야 함 (서버 B가 알아서 재고 롤백하도록)
        assertThat(response.hasFailures()).isTrue();
        assertThat(response.getFailedTraceIds()).containsExactlyInAnyOrder("trace-1", "trace-2");
    }

    @Test
    @DisplayName("중복 방어: 이미 처리 중인(claim 실패) 주문은 무시하고, 내가 잡은 것만 PG 결제를 진행한다.")
    void processBulkOrders_IgnoresAlreadyClaimed() {
        // Given: 2개의 메시지 중 trace-1만 선점 성공 (trace-2는 누군가 이미 처리 중)
        PurchaseMessage claimedMsg = messages.get(0);
        given(finalOrderRepository.claimOrders(anyList())).willReturn(List.of(claimedMsg));
        given(pgClient.processPayments(anyList())).willReturn(List.of());

        // When
        ServerCResponse response = orderProcessingService.processBulkOrders(messages);

        // Then: trace-1만 SUCCESS 마킹됨
        verify(finalOrderRepository).updateOrderStatus(List.of("trace-1"), "SUCCESS");
        verify(finalOrderRepository, never()).updateOrderStatus(eq(List.of("trace-2")), anyString());    }
}