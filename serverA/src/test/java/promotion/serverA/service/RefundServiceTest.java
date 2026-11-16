package promotion.serverA.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import promotion.serverA.client.UserQueryClient;
import promotion.serverA.dto.OrderStatusMessage;
import promotion.serverA.dto.PaymentCancelMessage;
import promotion.serverA.dto.StockSnapshotMessage;
import promotion.serverA.dto.response.RefundResponse;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;
import promotion.serverA.exception.RefundException;
import promotion.serverA.outbox.OutboxEventService;
import promotion.serverA.repository.GoodsRepository;
import promotion.serverA.repository.OrderRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final String ORDER_ID = "order-1";
    private static final String LOGIN_ID = "login-abc";
    private static final Long USER_ID = 42L;

    @Mock
    private UserQueryClient userQueryClient;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private GoodsRepository goodsRepository;
    @Mock
    private RedisStockService redisStockService;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private TransactionTemplate transactionTemplate;

    private RefundService refundService;

    @BeforeEach
    void setUp() {
        refundService = new RefundService(userQueryClient, orderRepository, goodsRepository, redisStockService,
                outboxEventService, transactionTemplate);
        ReflectionTestUtils.setField(refundService, "periodDays", 7L);
        // 트랜잭션 콜백을 그대로 실행한다
        lenient().when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
        lenient().doAnswer(inv -> {
            inv.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    private Order order(Long userId, OrderStatus status, LocalDateTime paidAt) {
        Order order = Order.builder()
                .orderId(ORDER_ID)
                .userId(userId)
                .goodsId(100L)
                .quantity(2)
                .clientIp("127.0.0.1")
                .status(status)
                .build();
        ReflectionTestUtils.setField(order, "paidAt", paidAt);
        return order;
    }

    private void givenUser() {
        given(userQueryClient.findUserId(LOGIN_ID)).willReturn(Optional.of(USER_ID));
    }

    private void assertRefundError(Runnable call, HttpStatus status, String errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(RefundException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getErrorCode()).isEqualTo(errorCode);
                });
    }

    @Test
    @DisplayName("PAID·7일 이내 본인 주문이면 REFUND_REQUESTED로 바꾸고 payment-cancel·order-status-update를 Outbox에 저장한다 (S-1)")
    void requestRefund_success() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.PAID, LocalDateTime.now().minusDays(1))));
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.PAID, OrderStatus.REFUND_REQUESTED))
                .willReturn(1);

        RefundResponse response = refundService.requestRefund(ORDER_ID, LOGIN_ID);

        assertThat(response).isEqualTo(new RefundResponse(ORDER_ID, "REFUND_REQUESTED"));
        verify(outboxEventService).save(ORDER_ID, "payment-cancel", new PaymentCancelMessage(ORDER_ID));
        verify(outboxEventService).save(ORDER_ID, "order-status-update",
                new OrderStatusMessage(ORDER_ID, USER_ID, "REFUND_REQUESTED"));
    }

    @Test
    @DisplayName("결제 후 7일째(경계 안쪽)는 환불할 수 있다 (P-3)")
    void requestRefund_lastDayAllowed() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.PAID, LocalDateTime.now().minusDays(7).plusMinutes(1))));
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.PAID, OrderStatus.REFUND_REQUESTED))
                .willReturn(1);

        assertThat(refundService.requestRefund(ORDER_ID, LOGIN_ID).status()).isEqualTo("REFUND_REQUESTED");
    }

    @Test
    @DisplayName("결제 후 7일이 지나면 409 REFUND_PERIOD_EXPIRED, 상태·Outbox를 건드리지 않는다 (S-3)")
    void requestRefund_periodExpired() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.PAID, LocalDateTime.now().minusDays(7).minusMinutes(1))));

        assertRefundError(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID), HttpStatus.CONFLICT, "REFUND_PERIOD_EXPIRED");
        verify(orderRepository, never()).updateStatusIfCurrent(anyString(), any(), any());
        verify(outboxEventService, never()).save(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("PAID가 아닌 주문은 409 REFUND_NOT_ALLOWED_STATUS (S-4)")
    void requestRefund_notPaid() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.FAILED, null)));

        assertRefundError(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID), HttpStatus.CONFLICT, "REFUND_NOT_ALLOWED_STATUS");
        verify(orderRepository, never()).updateStatusIfCurrent(anyString(), any(), any());
    }

    @Test
    @DisplayName("다른 사람의 주문은 404 ORDER_NOT_FOUND — 없는 주문과 같은 응답 (P-2, S-5)")
    void requestRefund_otherUsersOrder() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(999L, OrderStatus.PAID, LocalDateTime.now())));

        assertRefundError(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        verify(orderRepository, never()).updateStatusIfCurrent(anyString(), any(), any());
    }

    @Test
    @DisplayName("없는 주문은 404 ORDER_NOT_FOUND (S-5)")
    void requestRefund_orderMissing() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        assertRefundError(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("로그인 ID(X-User-Id)가 없으면 user-service를 부르지 않고 401 UNAUTHORIZED")
    void requestRefund_missingLoginId() {
        assertRefundError(() -> refundService.requestRefund(ORDER_ID, null), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        assertRefundError(() -> refundService.requestRefund(ORDER_ID, " "), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        verify(userQueryClient, never()).findUserId(anyString());
    }

    @Test
    @DisplayName("user-service에 없는 로그인 ID는 주문을 조회하지 않고 404 ORDER_NOT_FOUND")
    void requestRefund_unknownLoginId() {
        given(userQueryClient.findUserId(LOGIN_ID)).willReturn(Optional.empty());

        assertRefundError(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND");
        verify(orderRepository, never()).findByOrderId(anyString());
    }

    @Test
    @DisplayName("user-service 조회가 실패하면 503 USER_LOOKUP_UNAVAILABLE, 아무것도 바꾸지 않는다")
    void requestRefund_userLookupUnavailable() {
        given(userQueryClient.findUserId(LOGIN_ID)).willThrow(new IllegalStateException("user-service 없음"));

        assertRefundError(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID),
                HttpStatus.SERVICE_UNAVAILABLE, "USER_LOOKUP_UNAVAILABLE");
        verify(orderRepository, never()).findByOrderId(anyString());
    }

    @Test
    @DisplayName("이미 접수·완료된 주문은 새로 처리하지 않고 현재 상태로 성공 응답한다 (P-6)")
    void requestRefund_alreadyRequested() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.REFUNDED, LocalDateTime.now().minusDays(1))));

        assertThat(refundService.requestRefund(ORDER_ID, LOGIN_ID)).isEqualTo(new RefundResponse(ORDER_ID, "REFUNDED"));
        verify(orderRepository, never()).updateStatusIfCurrent(anyString(), any(), any());
        verify(outboxEventService, never()).save(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("동시 요청에서 조건부 UPDATE를 지면 새로 읽은 현재 상태로 성공 응답하고 Outbox는 저장하지 않는다 (S-6)")
    void requestRefund_lostRace() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID)).willReturn(
                Optional.of(order(USER_ID, OrderStatus.PAID, LocalDateTime.now().minusDays(1))),
                Optional.of(order(USER_ID, OrderStatus.REFUND_REQUESTED, LocalDateTime.now().minusDays(1))));
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.PAID, OrderStatus.REFUND_REQUESTED))
                .willReturn(0);

        assertThat(refundService.requestRefund(ORDER_ID, LOGIN_ID))
                .isEqualTo(new RefundResponse(ORDER_ID, "REFUND_REQUESTED"));
        verify(outboxEventService, never()).save(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("환불 완료: REFUND_REQUESTED 주문을 REFUNDED로 바꾸고 DB·Redis 재고 복구 + stock-snapshot·order-status-update Outbox, 구매 표시는 유지 (S-1, P-5, P-12)")
    void completeRefund_success() {
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED))
                .willReturn(1);
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.REFUNDED, LocalDateTime.now().minusDays(1))));
        given(redisStockService.restoreStockOnceForRefund(ORDER_ID, 100L, 2)).willReturn(true);
        given(redisStockService.getCurrentStock(100L)).willReturn(5L);

        refundService.completeRefund(ORDER_ID);

        verify(goodsRepository).increaseStockAtomically(100L, 2);
        verify(redisStockService).restoreStockOnceForRefund(ORDER_ID, 100L, 2);
        verify(redisStockService, never()).releaseUserPurchase(any(), any());
        verify(outboxEventService).save("100", "stock-snapshot", new StockSnapshotMessage(100L, 5L));
        verify(outboxEventService).save(ORDER_ID, "order-status-update",
                new OrderStatusMessage(ORDER_ID, USER_ID, "REFUNDED"));
    }

    @Test
    @DisplayName("환불 완료: 재고를 돌려놓기 전에 구매 표시를 만료 없이 다시 쓴다 — 구매 후 1시간이 지난 환불도 재구매 차단 (P-12, PR #55 리뷰 C1)")
    void completeRefund_marksPurchasePermanentlyBeforeRestoringStock() {
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED))
                .willReturn(1);
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.REFUNDED, LocalDateTime.now().minusDays(3))));
        given(redisStockService.restoreStockOnceForRefund(ORDER_ID, 100L, 2)).willReturn(true);

        refundService.completeRefund(ORDER_ID);

        InOrder inOrder = inOrder(redisStockService);
        inOrder.verify(redisStockService).markUserPurchasedPermanently(USER_ID, 100L);
        inOrder.verify(redisStockService).restoreStockOnceForRefund(ORDER_ID, 100L, 2);
    }

    @Test
    @DisplayName("환불 완료 재시도: Redis 재고가 이미 복구됐으면(방어 키 있음) DB 재고·Outbox만 다시 하고 Redis는 올리지 않는다 (PR #55 리뷰 C3)")
    void completeRefund_retryDoesNotRestoreRedisTwice() {
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED))
                .willReturn(1);
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.REFUNDED, LocalDateTime.now().minusDays(1))));
        given(redisStockService.restoreStockOnceForRefund(ORDER_ID, 100L, 2)).willReturn(false);
        given(redisStockService.getCurrentStock(100L)).willReturn(5L);

        refundService.completeRefund(ORDER_ID);

        verify(goodsRepository).increaseStockAtomically(100L, 2);
        verify(redisStockService, never()).releaseStock(any(), anyInt()); // 재고 증가는 restoreStockOnceForRefund 안에서만 (재시도면 false)
        verify(redisStockService).markUserPurchasedPermanently(USER_ID, 100L);
        verify(outboxEventService).save("100", "stock-snapshot", new StockSnapshotMessage(100L, 5L));
        verify(outboxEventService).save(ORDER_ID, "order-status-update",
                new OrderStatusMessage(ORDER_ID, USER_ID, "REFUNDED"));
    }

    @Test
    @DisplayName("paid_at이 없는 PAID 주문(롤링 배포 중 옛 파드가 만든 주문)은 updated_at으로 기간을 판단한다 — 7일 이내면 접수 (PR #55 리뷰 C2)")
    void requestRefund_paidAtMissingFallsBackToUpdatedAt() {
        givenUser();
        Order order = order(USER_ID, OrderStatus.PAID, null);
        ReflectionTestUtils.setField(order, "updatedAt", LocalDateTime.now().minusDays(1));
        given(orderRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(order));
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.PAID, OrderStatus.REFUND_REQUESTED))
                .willReturn(1);

        assertThat(refundService.requestRefund(ORDER_ID, LOGIN_ID).status()).isEqualTo("REFUND_REQUESTED");
    }

    @Test
    @DisplayName("paid_at이 없고 updated_at도 7일이 지났으면 409 REFUND_PERIOD_EXPIRED (500이 아님)")
    void requestRefund_paidAtMissingAndUpdatedAtExpired() {
        givenUser();
        Order order = order(USER_ID, OrderStatus.PAID, null);
        ReflectionTestUtils.setField(order, "updatedAt", LocalDateTime.now().minusDays(8));
        given(orderRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(order));

        assertRefundError(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID), HttpStatus.CONFLICT, "REFUND_PERIOD_EXPIRED");
    }

    @Test
    @DisplayName("환불 완료: 환불 접수 주문이 아니면(망취소·중복 수신) 재고·Outbox를 건드리지 않는다 (P-9)")
    void completeRefund_notRefundRequested() {
        given(orderRepository.updateStatusIfCurrent(ORDER_ID, OrderStatus.REFUND_REQUESTED, OrderStatus.REFUNDED))
                .willReturn(0);

        refundService.completeRefund(ORDER_ID);

        verify(goodsRepository, never()).increaseStockAtomically(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(redisStockService, never()).restoreStockOnceForRefund(anyString(), any(), anyInt());
        verify(outboxEventService, never()).save(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("기간 문구에는 설정된 환불 기간 일수가 들어간다")
    void periodExpiredMessageUsesConfiguredDays() {
        givenUser();
        given(orderRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(order(USER_ID, OrderStatus.PAID, LocalDateTime.now().minusDays(30))));

        assertThatThrownBy(() -> refundService.requestRefund(ORDER_ID, LOGIN_ID))
                .hasMessage("환불 기간(결제 후 7일)이 지났습니다");
        verify(outboxEventService, never()).save(eq(ORDER_ID), anyString(), any());
    }
}
