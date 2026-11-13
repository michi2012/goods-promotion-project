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

    private static final String ORDER_ID = "trace-1234";
    private static final long ORDER_AMOUNT = 30_000L;

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

    // 금액이 담긴 새 형식 메시지 (15,000원 × 2개)
    private static PurchaseMessage message() {
        return new PurchaseMessage(ORDER_ID, 1L, 4L, 2, 15_000L, ORDER_AMOUNT, "KRW",
                "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1");
    }

    // 배포 중 옛 serverA가 보낸 금액 없는 메시지
    private static PurchaseMessage legacyMessage() {
        return new PurchaseMessage(ORDER_ID, 1L, 4L, 2, null, null, null,
                "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1");
    }

    private double counter(String name, String... tags) {
        return meterRegistry.get(name).tags(tags).counter().count();
    }

    @Test
    @DisplayName("결제 성공 시 PAID 상태로 업데이트하고 Outbox에 성공 이벤트를 저장한다")
    void processPayment_Success() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenReturn(PgTransactionResult.approved(ORDER_AMOUNT));

        // when
        paymentService.processPayment(message());

        // then
        verify(paymentRepository).claimOrder(any());
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "PAID");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-result"),
                eq(new PaymentResultMessage(ORDER_ID, true, null)));
        verify(pgClient, never()).cancelPayments(anyList());
    }

    @Test
    @DisplayName("결제 실패 시 FAILED 상태로 업데이트하고 Outbox에 실패 이벤트를 저장한다")
    void processPayment_Fail() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenReturn(PgTransactionResult.rejected());

        // when
        paymentService.processPayment(message());

        // then
        verify(paymentRepository).claimOrder(any());
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "FAILED");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-result"),
                eq(new PaymentResultMessage(ORDER_ID, false, "결제 거절")));
    }

    @Test
    @DisplayName("PG 승인 금액이 주문 금액과 다르면 즉시 망취소하고 FAILED + 금액 불일치 이벤트를 저장한다")
    void processPayment_AmountMismatch_CancelsAndFails() {
        // given: 주문 30,000원인데 PG가 3,000원으로 승인
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenReturn(PgTransactionResult.approved(3_000L));

        // when
        paymentService.processPayment(message());

        // then
        verify(pgClient).cancelPayments(List.of(ORDER_ID));
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "FAILED");
        verify(paymentRepository, never()).updateOrderStatus(List.of(ORDER_ID), "PAID");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-result"),
                eq(new PaymentResultMessage(ORDER_ID, false, "금액 불일치")));
        assertThat(counter("business_payment_error_total", "type", "amount_mismatch")).isEqualTo(1.0);
        assertThat(counter("business_payment_success_total")).isZero();
    }

    @Test
    @DisplayName("PG가 승인 금액을 돌려주지 않으면 검증할 수 없으므로 금액 불일치로 망취소한다")
    void processPayment_ApprovedWithoutAmount_TreatedAsMismatch() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenReturn(PgTransactionResult.approved(null));

        // when
        paymentService.processPayment(message());

        // then
        verify(pgClient).cancelPayments(List.of(ORDER_ID));
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "FAILED");
    }

    @Test
    @DisplayName("금액 없는 옛 메시지는 금액 검증 없이 기존 방식으로 처리하고 경고 메트릭을 올린다")
    void processPayment_LegacyMessage_SkipsVerification() {
        // given: 옛 메시지라 모의 PG도 승인 금액 null로 응답
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenReturn(PgTransactionResult.approved(null));

        // when
        paymentService.processPayment(legacyMessage());

        // then
        verify(pgClient, never()).cancelPayments(anyList());
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "PAID");
        assertThat(counter("business_payment_legacy_without_amount_total")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("중복 수신된 메시지는 claimOrders 선점 실패 시 PG 호출 없이 무시된다")
    void processPayment_DuplicateMessage() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(false);

        // when
        paymentService.processPayment(message());

        // then
        verify(pgClient, never()).processPayment(any());
        verify(paymentRepository, never()).updateOrderStatus(anyList(), any());
        verify(outboxEventService, never()).save(any(), any(), any());
    }

    @Test
    @DisplayName("애매한 응답 후 조회가 NOT_FOUND면 1회 재시도하고, 재시도가 성공하면 PAID로 처리한다")
    void processPayment_AmbiguousThenNotFoundThenRetrySuccess() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any()))
                .thenThrow(new PgAmbiguousResponseException("ambiguous"))
                .thenReturn(PgTransactionResult.approved(ORDER_AMOUNT));
        when(pgClient.queryTransaction(ORDER_ID)).thenReturn(PgTransactionResult.notFound());

        // when
        paymentService.processPayment(message());

        // then
        verify(pgClient, times(2)).processPayment(any());
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "PAID");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-result"),
                eq(new PaymentResultMessage(ORDER_ID, true, null)));
    }

    @Test
    @DisplayName("애매한 응답 후 조회가 APPROVED면 재시도 없이 그 결과로 PAID를 확정한다")
    void processPayment_AmbiguousThenApprovedByQuery() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenThrow(new PgAmbiguousResponseException("ambiguous"));
        when(pgClient.queryTransaction(ORDER_ID)).thenReturn(PgTransactionResult.approved(ORDER_AMOUNT));

        // when
        paymentService.processPayment(message());

        // then
        verify(pgClient, times(1)).processPayment(any());
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "PAID");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-result"),
                eq(new PaymentResultMessage(ORDER_ID, true, null)));
    }

    @Test
    @DisplayName("애매한 응답 후 조회로 확정된 승인 금액도 검증해, 다르면 망취소한다")
    void processPayment_AmbiguousThenApprovedByQuery_AmountMismatch() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any())).thenThrow(new PgAmbiguousResponseException("ambiguous"));
        when(pgClient.queryTransaction(ORDER_ID)).thenReturn(PgTransactionResult.approved(1_000L));

        // when
        paymentService.processPayment(message());

        // then
        verify(pgClient).cancelPayments(List.of(ORDER_ID));
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "FAILED");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-result"),
                eq(new PaymentResultMessage(ORDER_ID, false, "금액 불일치")));
    }

    @Test
    @DisplayName("재시도도 애매한 응답이면 PG 시스템 장애로 분류해 FAILED 처리한다")
    void processPayment_AmbiguousRetryExhausted() {
        // given
        when(paymentRepository.claimOrder(any())).thenReturn(true);
        when(pgClient.processPayment(any()))
                .thenThrow(new PgAmbiguousResponseException("ambiguous-1"))
                .thenThrow(new PgAmbiguousResponseException("ambiguous-2"));
        when(pgClient.queryTransaction(ORDER_ID)).thenReturn(PgTransactionResult.notFound());

        // when
        paymentService.processPayment(message());

        // then
        verify(pgClient, times(2)).processPayment(any());
        verify(paymentRepository).updateOrderStatus(List.of(ORDER_ID), "FAILED");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-result"),
                eq(new PaymentResultMessage(ORDER_ID, false, "PG 시스템 장애")));
    }

    @Test
    @DisplayName("orderId로 결제 정보를 조회하면 PaymentResponse를 반환한다")
    void getByOrderId_Success() {
        // given
        PaymentResponse expected = new PaymentResponse(ORDER_ID, 1L, 4L, 1, 15_000L, "KRW", "CARD", "PAID", LocalDateTime.now());
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(expected));

        // when
        PaymentResponse result = paymentService.getByOrderId(ORDER_ID);

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
                new PaymentResponse("order-1", userId, 4L, 1, 15_000L, "KRW", "CARD", "PAID", LocalDateTime.now()),
                new PaymentResponse("order-2", userId, 5L, 2, 30_000L, "KRW", "CARD", "PAID", LocalDateTime.now())
        );
        when(paymentRepository.findByUserId(userId, 0, 20)).thenReturn(expected);

        // when
        List<PaymentResponse> result = paymentService.getByUserId(userId, 0, 20);

        // then
        assertThat(result).hasSize(2).isEqualTo(expected);
    }
}
