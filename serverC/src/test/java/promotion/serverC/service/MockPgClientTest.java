package promotion.serverC.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import promotion.serverC.dto.PurchaseMessage;
import promotion.serverC.exception.PgAmbiguousResponseException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MockPgClientTest {

    private SimpleMeterRegistry meterRegistry;
    private MockPgLedger ledger;
    private MockPgClient pgClient;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ledger = mock(MockPgLedger.class);
        pgClient = new MockPgClient(meterRegistry, ledger);
        pgClient.initMetrics();
    }

    @Test
    @DisplayName("처음 취소하면 CANCELLED를 돌려주고 환불 요청 건수가 1 늘어난다")
    void cancelFirstTime() {
        PgCancelResult result = pgClient.cancelPayment("order-1");

        assertThat(result).isEqualTo(PgCancelResult.CANCELLED);
        assertThat(meterRegistry.counter("business_payment_pg_refund_request_total").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 주문을 다시 취소하면 ALREADY_CANCELLED를 돌려주고 실제 취소는 다시 일어나지 않는다")
    void cancelTwiceIsIdempotent() {
        pgClient.cancelPayment("order-1");

        PgCancelResult second = pgClient.cancelPayment("order-1");

        assertThat(second).isEqualTo(PgCancelResult.ALREADY_CANCELLED);
        assertThat(meterRegistry.counter("business_payment_pg_refund_request_total").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("다른 주문의 취소는 서로 영향을 주지 않는다")
    void cancelDifferentOrders() {
        pgClient.cancelPayment("order-1");

        PgCancelResult other = pgClient.cancelPayment("order-2");

        assertThat(other).isEqualTo(PgCancelResult.CANCELLED);
    }

    @Test
    @DisplayName("승인 결과가 남은 거래는 원장에 승인 1건으로 기록되고, 거절·미처리 거래는 기록되지 않는다")
    void approvalIsRecordedOnlyWhenApproved() {
        // 모의 PG는 무작위로 애매한 응답·거절을 내므로, 분기와 무관한 불변식으로 검증한다:
        // "PG가 승인으로 기억하는 거래 ⇔ 원장에 승인 1건"
        for (int i = 0; i < 3; i++) {
            String orderId = "order-approve-" + i;
            try {
                pgClient.processPayment(message(orderId));
            } catch (PgAmbiguousResponseException ignored) {
                // 애매한 응답: 내부적으로 처리됐을 수도, 안 됐을 수도 있다
            }
            boolean approved = pgClient.queryTransaction(orderId).isApproved();

            verify(ledger, times(approved ? 1 : 0))
                    .record(eq(orderId), eq(MockPgLedger.APPROVE), eq(30_000L), any(LocalDateTime.class));
        }
    }

    @Test
    @DisplayName("멱등 키로 다시 요청하면 원장에 다시 기록하지 않는다")
    void idempotentRetryDoesNotRecordAgain() {
        String orderId = "order-retry";
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                pgClient.processPayment(message(orderId));
            } catch (PgAmbiguousResponseException ignored) {
            }
        }

        // 몇 번 요청하든 PG가 승인으로 기억하면 정확히 1건, 아니면 0건 (분기와 무관하게 결정적으로 검증)
        boolean approved = pgClient.queryTransaction(orderId).isApproved();
        verify(ledger, times(approved ? 1 : 0)).record(eq(orderId), eq(MockPgLedger.APPROVE), any(), any());
    }

    @Test
    @DisplayName("처음 취소하면 승인 금액으로 취소 1건을 기록하고, 다시 취소해도 추가 기록이 없다")
    void cancelIsRecordedOnce() {
        pgClient.cancelPayment("order-1");
        pgClient.cancelPayment("order-1");

        // 이 테스트에서는 승인 이력이 없어 취소 금액을 알 수 없다 (재기동으로 승인 결과를 잊은 경우와 같음)
        verify(ledger, times(1)).record(eq("order-1"), eq(MockPgLedger.CANCEL), isNull(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("원장 기록이 실패해도 취소는 정상 완료된다")
    void ledgerFailureDoesNotBreakCancel() {
        doThrow(new RuntimeException("DB 장애")).when(ledger).record(anyString(), anyString(), any(), any());

        PgCancelResult result = pgClient.cancelPayment("order-1");

        assertThat(result).isEqualTo(PgCancelResult.CANCELLED);
    }

    private static PurchaseMessage message(String orderId) {
        return new PurchaseMessage(orderId, 1L, 4L, 2, 15_000L, 30_000L, "KRW",
                "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1");
    }
}
