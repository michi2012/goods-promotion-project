package promotion.serverC.service;

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
import promotion.serverC.dto.PaymentResponse;
import promotion.serverC.dto.PaymentResultMessage;
import promotion.serverC.dto.PurchaseMessage;
import promotion.serverC.exception.PaymentNotFoundException;
import promotion.serverC.exception.PgAmbiguousResponseException;
import promotion.serverC.outbox.OutboxEventService;
import promotion.serverC.repository.PaymentRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

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
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenReturn(true);

        // when
        paymentService.processPayment(message);

        // then
        verify(paymentRepository).claimOrder(any());
        verify(paymentRepository).updateOrderStatus(List.of(orderId), "PAID");
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
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenReturn(false);

        // when
        paymentService.processPayment(message);

        // then
        verify(paymentRepository).claimOrder(any());
        verify(paymentRepository).updateOrderStatus(List.of(orderId), "FAILED");
        verify(outboxEventService).save(eq(orderId), eq("payment-result"),
                eq(new PaymentResultMessage(orderId, false, "결제 거절")));
    }

    @Test
    @DisplayName("중복 수신된 메시지는 claimOrders 선점 실패 시 PG 호출 없이 무시된다")
    void processPayment_DuplicateMessage() {
        // given
        PurchaseMessage message = new PurchaseMessage(
                "trace-1234", 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );
        when(paymentRepository.claimOrder(any())).thenReturn(false);

        // when
        paymentService.processPayment(message);

        // then
        verify(pgClient, never()).processPayment(any());
        verify(paymentRepository, never()).updateOrderStatus(anyList(), any());
        verify(outboxEventService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("애매한 응답 후 조회가 NOT_FOUND면 1회 재시도하고, 재시도가 성공하면 PAID로 처리한다")
    void processPayment_AmbiguousThenNotFoundThenRetrySuccess() {
        // given
        String orderId = "trace-1234";
        PurchaseMessage message = new PurchaseMessage(
                orderId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any()))
                .thenThrow(new PgAmbiguousResponseException("ambiguous"))
                .thenReturn(true);
        when(pgClient.queryTransaction(orderId)).thenReturn(PgTransactionStatus.NOT_FOUND);

        // when
        paymentService.processPayment(message);

        // then
        verify(pgClient, times(2)).processPayment(any());
        verify(paymentRepository).updateOrderStatus(List.of(orderId), "PAID");
        verify(outboxEventService).save(eq(orderId), eq("payment-result"),
                eq(new PaymentResultMessage(orderId, true, null)));
    }

    @Test
    @DisplayName("애매한 응답 후 조회가 APPROVED면 재시도 없이 그 결과로 PAID를 확정한다")
    void processPayment_AmbiguousThenApprovedByQuery() {
        // given
        String orderId = "trace-1234";
        PurchaseMessage message = new PurchaseMessage(
                orderId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenThrow(new PgAmbiguousResponseException("ambiguous"));
        when(pgClient.queryTransaction(orderId)).thenReturn(PgTransactionStatus.APPROVED);

        // when
        paymentService.processPayment(message);

        // then
        verify(pgClient, times(1)).processPayment(any());
        verify(paymentRepository).updateOrderStatus(List.of(orderId), "PAID");
        verify(outboxEventService).save(eq(orderId), eq("payment-result"),
                eq(new PaymentResultMessage(orderId, true, null)));
    }

    @Test
    @DisplayName("재시도도 애매한 응답이면 PG 시스템 장애로 분류해 FAILED 처리한다")
    void processPayment_AmbiguousRetryExhausted() {
        // given
        String orderId = "trace-1234";
        PurchaseMessage message = new PurchaseMessage(
                orderId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1"
        );
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any()))
                .thenThrow(new PgAmbiguousResponseException("ambiguous-1"))
                .thenThrow(new PgAmbiguousResponseException("ambiguous-2"));
        when(pgClient.queryTransaction(orderId)).thenReturn(PgTransactionStatus.NOT_FOUND);

        // when
        paymentService.processPayment(message);

        // then
        verify(pgClient, times(2)).processPayment(any());
        verify(paymentRepository).updateOrderStatus(List.of(orderId), "FAILED");
        verify(outboxEventService).save(eq(orderId), eq("payment-result"),
                eq(new PaymentResultMessage(orderId, false, "PG 시스템 장애")));
    }

    @Test
    @DisplayName("orderId로 결제 정보를 조회하면 PaymentResponse를 반환한다")
    void getByOrderId_Success() {
        // given
        String orderId = "trace-1234";
        PaymentResponse expected = new PaymentResponse(orderId, 1L, 4L, 1, "CARD", "PAID", LocalDateTime.now());
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(expected));

        // when
        PaymentResponse result = paymentService.getByOrderId(orderId);

        // then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("존재하지 않는 orderId 조회 시 PaymentNotFoundException이 발생한다")
    void getByOrderId_NotFound() {
        // given
        String orderId = "not-exist";
        when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> paymentService.getByOrderId(orderId))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    @DisplayName("userId로 결제 내역을 조회하면 repository 결과를 그대로 반환한다")
    void getByUserId_ReturnsList() {
        // given
        Long userId = 1L;
        List<PaymentResponse> expected = List.of(
                new PaymentResponse("order-1", userId, 4L, 1, "CARD", "PAID", LocalDateTime.now()),
                new PaymentResponse("order-2", userId, 5L, 2, "CARD", "PAID", LocalDateTime.now())
        );
        when(paymentRepository.findByUserId(userId, 0, 20)).thenReturn(expected);

        // when
        List<PaymentResponse> result = paymentService.getByUserId(userId, 0, 20);

        // then
        assertThat(result).hasSize(2).isEqualTo(expected);
    }
}