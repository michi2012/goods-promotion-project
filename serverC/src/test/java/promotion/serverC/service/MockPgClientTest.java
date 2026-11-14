package promotion.serverC.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockPgClientTest {

    private SimpleMeterRegistry meterRegistry;
    private MockPgClient pgClient;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        pgClient = new MockPgClient(meterRegistry);
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
}
