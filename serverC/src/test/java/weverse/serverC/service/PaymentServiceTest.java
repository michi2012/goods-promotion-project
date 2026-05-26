package weverse.serverC.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverC.dto.PaymentResultMessage;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.outbox.OutboxEventService;
import weverse.serverC.repository.FinalOrderRepository;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private FinalOrderRepository finalOrderRepository;

    @Mock
    private PgClient pgClient;

    @Mock
    private OutboxEventService outboxEventService;

    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService.initMetrics();
    }

    @Test
    @DisplayName("결제 성공 시 PAID 상태로 업데이트하고 Outbox에 성공 이벤트를 저장한다")
    void processPayment_Success() {
        // given
        String orderId = "trace-1234";
        PurchaseMessage message = new PurchaseMessage(
                orderId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );
        when(pgClient.processPayments(anyList())).thenReturn(Collections.emptyList());

        // when
        paymentService.processPayment(message);

        // then
        verify(finalOrderRepository).claimOrders(anyList());
        verify(finalOrderRepository).updateOrderStatus(List.of(orderId), "PAID");
        verify(outboxEventService).save(eq(orderId), eq("payment-result"),
                eq(new PaymentResultMessage(orderId, true, null)));
    }

    @Test
    @DisplayName("결제 실패 시 FAILED 상태로 업데이트하고 Outbox에 실패 이벤트를 저장한다")
    void processPayment_Fail() {
        // given
        String orderId = "trace-1234";
        PurchaseMessage message = new PurchaseMessage(
                orderId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );
        when(pgClient.processPayments(anyList())).thenReturn(List.of(orderId));

        // when
        paymentService.processPayment(message);

        // then
        verify(finalOrderRepository).claimOrders(anyList());
        verify(finalOrderRepository).updateOrderStatus(List.of(orderId), "FAILED");
        verify(outboxEventService).save(eq(orderId), eq("payment-result"),
                eq(new PaymentResultMessage(orderId, false, "결제 거절")));
    }
}