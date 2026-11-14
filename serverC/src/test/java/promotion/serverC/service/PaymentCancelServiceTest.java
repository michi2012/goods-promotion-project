package promotion.serverC.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import promotion.serverC.dto.CancelFailureResponse;
import promotion.serverC.dto.PaymentCancelledMessage;
import promotion.serverC.dto.PaymentResponse;
import promotion.serverC.entity.CancelFailureStatus;
import promotion.serverC.entity.PaymentCancelFailure;
import promotion.serverC.exception.CancelFailureNotFoundException;
import promotion.serverC.exception.InvalidCancelFailureStatusException;
import promotion.serverC.exception.PgPaymentException;
import promotion.serverC.outbox.OutboxEventService;
import promotion.serverC.repository.PaymentCancelFailureRepository;
import promotion.serverC.repository.PaymentRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCancelServiceTest {

    private static final String ORDER_ID = "order-1";

    @Mock
    private PgClient pgClient;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentCancelFailureRepository failureRepository;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private SimpleMeterRegistry meterRegistry;
    private PaymentCancelService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new PaymentCancelService(pgClient, paymentRepository, failureRepository,
                outboxEventService, transactionTemplate, meterRegistry);
        service.initMetrics();
        ReflectionTestUtils.setField(service, "retryIntervalMinutes", 10L);
        ReflectionTestUtils.setField(service, "maxAttempts", 6);
        ReflectionTestUtils.setField(service, "leaseMinutes", 5L);
        ReflectionTestUtils.setField(service, "batchSize", 50);
        // 트랜잭션 콜백을 그대로 실행한다
        lenient().doAnswer(inv -> {
            inv.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    @Test
    @DisplayName("DLT 도착 시 실패 기록이 없으면 RETRYING으로 새로 만든다 (다음 재시도는 10분 뒤)")
    void recordDltArrivalNew() {
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());
        LocalDateTime before = LocalDateTime.now();

        service.recordDltArrival(ORDER_ID, "PG 장애");

        ArgumentCaptor<PaymentCancelFailure> saved = ArgumentCaptor.forClass(PaymentCancelFailure.class);
        verify(failureRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CancelFailureStatus.RETRYING);
        assertThat(saved.getValue().getNextRetryAt()).isAfterOrEqualTo(before.plusMinutes(10));
        assertThat(saved.getValue().getLastError()).isEqualTo("PG 장애");
    }

    @Test
    @DisplayName("DLT 도착 시 실패 기록이 있으면 새로 만들지 않고 기존 행을 갱신한다")
    void recordDltArrivalExisting() {
        PaymentCancelFailure failure = PaymentCancelFailure.retrying(ORDER_ID, "e", LocalDateTime.now());
        failure.recordAutoRetryFailure("e1", 6, LocalDateTime.now());
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));

        service.recordDltArrival(ORDER_ID, "again");

        verify(failureRepository, never()).save(any());
        assertThat(failure.getAttempts()).isEqualTo(1);
        assertThat(failure.getLastError()).isEqualTo("again");
    }

    @Test
    @DisplayName("선점에 실패한 행은 재시도하지 않는다")
    void retryDueSkipsUnclaimed() {
        LocalDateTime now = LocalDateTime.now();
        PaymentCancelFailure failure = PaymentCancelFailure.retrying(ORDER_ID, "e", now.minusMinutes(1));
        when(failureRepository.findDueForRetry(eq(now), any())).thenReturn(List.of(failure));
        when(failureRepository.claimForRetry(any(), eq(now), eq(now.plusMinutes(5)))).thenReturn(0);

        int processed = service.retryDue(now);

        assertThat(processed).isZero();
        verify(pgClient, never()).cancelPayment(anyString());
    }

    @Test
    @DisplayName("재시도가 성공하면 취소를 완료하고 실패 기록을 RESOLVED로 바꾼다")
    void retryDueSuccess() {
        LocalDateTime now = LocalDateTime.now();
        PaymentCancelFailure failure = PaymentCancelFailure.retrying(ORDER_ID, "e", now.minusMinutes(1));
        givenDue(now, failure);
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenReturn(PgCancelResult.CANCELLED);
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));

        int processed = service.retryDue(now);

        assertThat(processed).isEqualTo(1);
        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.RESOLVED);
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-cancelled"), any());
    }

    @Test
    @DisplayName("재시도가 실패하면 횟수를 늘리고 10분 뒤로 다시 잡는다")
    void retryDueFailureBelowMax() {
        LocalDateTime now = LocalDateTime.now();
        PaymentCancelFailure failure = PaymentCancelFailure.retrying(ORDER_ID, "e", now.minusMinutes(1));
        givenDue(now, failure);
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenThrow(new PgPaymentException("PG 장애"));
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));

        service.retryDue(now);

        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.RETRYING);
        assertThat(failure.getAttempts()).isEqualTo(1);
        assertThat(failure.getNextRetryAt()).isEqualTo(now.plusMinutes(10));
        assertThat(meterRegistry.counter("business_payment_cancel_retry_exhausted_total").count()).isZero();
    }

    @Test
    @DisplayName("마지막 재시도까지 실패하면 EXHAUSTED가 되고 관리자 처리 메트릭이 오른다")
    void retryDueFailureExhausted() {
        LocalDateTime now = LocalDateTime.now();
        PaymentCancelFailure failure = PaymentCancelFailure.retrying(ORDER_ID, "e", now.minusMinutes(1));
        for (int i = 0; i < 5; i++) {
            failure.recordAutoRetryFailure("e", 6, now.minusMinutes(1));
        }
        givenDue(now, failure);
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenThrow(new PgPaymentException("PG 장애"));
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));

        service.retryDue(now);

        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(failure.getAttempts()).isEqualTo(6);
        assertThat(meterRegistry.counter("business_payment_cancel_retry_exhausted_total").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("목록 조회는 상태를 주지 않으면 EXHAUSTED를 조회한다")
    void listFailuresDefaultsToExhausted() {
        when(failureRepository.findByStatusOrderByIdDesc(CancelFailureStatus.EXHAUSTED))
                .thenReturn(List.of(PaymentCancelFailure.exhausted(ORDER_ID, "e")));

        List<CancelFailureResponse> result = service.listFailures(null);

        assertThat(result).extracting(CancelFailureResponse::orderId).containsExactly(ORDER_ID);
    }

    @Test
    @DisplayName("목록 조회에 RESOLVED나 모르는 상태를 주면 거절한다")
    void listFailuresRejectsInvalidStatus() {
        assertThatThrownBy(() -> service.listFailures("RESOLVED"))
                .isInstanceOf(InvalidCancelFailureStatusException.class);
        assertThatThrownBy(() -> service.listFailures("WHATEVER"))
                .isInstanceOf(InvalidCancelFailureStatusException.class);
    }

    @Test
    @DisplayName("관리자 재처리가 성공하면 RESOLVED를 돌려준다")
    void retryManuallySuccess() {
        PaymentCancelFailure failure = PaymentCancelFailure.exhausted(ORDER_ID, "e");
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenReturn(PgCancelResult.CANCELLED);

        CancelFailureResponse result = service.retryManually(ORDER_ID);

        assertThat(result.status()).isEqualTo(CancelFailureStatus.RESOLVED);
    }

    @Test
    @DisplayName("관리자 재처리가 실패하면 EXHAUSTED를 유지하고 횟수만 늘린다")
    void retryManuallyFailure() {
        PaymentCancelFailure failure = PaymentCancelFailure.exhausted(ORDER_ID, "e");
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenThrow(new PgPaymentException("PG 장애"));

        CancelFailureResponse result = service.retryManually(ORDER_ID);

        assertThat(result.status()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.lastError()).isEqualTo("PG 장애");
    }

    @Test
    @DisplayName("이미 RESOLVED면 PG를 부르지 않고 현재 상태를 돌려준다")
    void retryManuallyAlreadyResolved() {
        PaymentCancelFailure failure = PaymentCancelFailure.exhausted(ORDER_ID, "e");
        failure.markResolved();
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));

        CancelFailureResponse result = service.retryManually(ORDER_ID);

        assertThat(result.status()).isEqualTo(CancelFailureStatus.RESOLVED);
        verify(pgClient, never()).cancelPayment(anyString());
    }

    @Test
    @DisplayName("실패 기록이 없는 주문을 재처리하면 예외를 던진다")
    void retryManuallyNotFound() {
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryManually(ORDER_ID))
                .isInstanceOf(CancelFailureNotFoundException.class);
    }

    private void givenDue(LocalDateTime now, PaymentCancelFailure failure) {
        when(failureRepository.findDueForRetry(eq(now), any())).thenReturn(List.of(failure));
        when(failureRepository.claimForRetry(any(), eq(now), eq(now.plusMinutes(5)))).thenReturn(1);
    }

    @Test
    @DisplayName("PAID 결제면 PG 취소 후 CANCELLED로 바꾸고 payment-cancelled를 Outbox에 저장한다")
    void cancelPaidPayment() {
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenReturn(PgCancelResult.CANCELLED);

        service.cancel(ORDER_ID);

        verify(pgClient).cancelPayment(ORDER_ID);
        verify(paymentRepository).updateStatusIfCurrent(ORDER_ID, "PAID", "CANCELLED");
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-cancelled"), payload.capture());
        assertThat(((PaymentCancelledMessage) payload.getValue()).orderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("PG가 이미 취소됨을 돌려줘도 취소 완료로 처리한다")
    void cancelAlreadyCancelledAtPg() {
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenReturn(PgCancelResult.ALREADY_CANCELLED);

        service.cancel(ORDER_ID);

        verify(paymentRepository).updateStatusIfCurrent(ORDER_ID, "PAID", "CANCELLED");
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-cancelled"), any());
    }

    @Test
    @DisplayName("이미 CANCELLED인 결제면 PG를 부르지 않고 payment-cancelled만 다시 저장한다")
    void cancelAlreadyCancelledPayment() {
        givenPaymentStatus("CANCELLED");

        service.cancel(ORDER_ID);

        verify(pgClient, never()).cancelPayment(anyString());
        verify(outboxEventService).save(eq(ORDER_ID), eq("payment-cancelled"), any());
    }

    @Test
    @DisplayName("취소가 완료되면 남아 있던 실패 기록을 RESOLVED로 바꾼다")
    void cancelResolvesExistingFailure() {
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenReturn(PgCancelResult.CANCELLED);
        PaymentCancelFailure failure = PaymentCancelFailure.retrying(ORDER_ID, "e", LocalDateTime.now());
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));

        service.cancel(ORDER_ID);

        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.RESOLVED);
    }

    @Test
    @DisplayName("PG 취소가 실패하면 예외를 그대로 던지고 상태·Outbox를 건드리지 않는다")
    void cancelPgFailure() {
        givenPaymentStatus("PAID");
        when(pgClient.cancelPayment(ORDER_ID)).thenThrow(new PgPaymentException("PG 장애"));

        assertThatThrownBy(() -> service.cancel(ORDER_ID)).isInstanceOf(PgPaymentException.class);

        verify(paymentRepository, never()).updateStatusIfCurrent(anyString(), anyString(), anyString());
        verify(outboxEventService, never()).save(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("결제가 없으면 PG를 부르지 않고 EXHAUSTED로 기록하고 관리자 처리 메트릭을 올린다")
    void cancelMissingPayment() {
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.empty());

        service.cancel(ORDER_ID);

        verify(pgClient, never()).cancelPayment(anyString());
        ArgumentCaptor<PaymentCancelFailure> saved = ArgumentCaptor.forClass(PaymentCancelFailure.class);
        verify(failureRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(meterRegistry.counter("business_payment_cancel_retry_exhausted_total").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("결제가 PAID·CANCELLED가 아니면 PG를 부르지 않고 기존 실패 기록을 EXHAUSTED로 바꾼다")
    void cancelInvalidStatusWithExistingFailure() {
        givenPaymentStatus("FAILED");
        PaymentCancelFailure failure = PaymentCancelFailure.retrying(ORDER_ID, "e", LocalDateTime.now());
        when(failureRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(failure));

        service.cancel(ORDER_ID);

        verify(pgClient, never()).cancelPayment(anyString());
        verify(failureRepository, never()).save(any());
        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(failure.getLastError()).contains("FAILED");
    }

    private void givenPaymentStatus(String status) {
        when(paymentRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(new PaymentResponse(
                ORDER_ID, 1L, 1L, 1, 1000L, "KRW", "CARD", status, LocalDateTime.now())));
    }
}
