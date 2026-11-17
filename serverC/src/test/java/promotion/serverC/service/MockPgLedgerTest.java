package promotion.serverC.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import promotion.serverC.support.AbstractRepositoryTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MySQL(Flyway 스키마)에서 모의 PG 원장의 기록·조회를 검증한다.
 * REQUIRES_NEW가 바깥 롤백과 무관하게 커밋되는지 보려면 테스트 트랜잭션을 쓰지 않아야 한다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(MockPgLedger.class)
class MockPgLedgerTest extends AbstractRepositoryTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 11, 16, 0, 0);

    @Autowired
    private MockPgLedger ledger;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM mock_pg_transactions");
    }

    @Test
    @DisplayName("같은 주문의 같은 이벤트를 다시 기록해도 한 행만 남고 처음 기록이 유지된다")
    void recordIsIdempotent() {
        ledger.record("order-1", MockPgLedger.APPROVE, 30_000L, DAY.plusHours(10));
        ledger.record("order-1", MockPgLedger.APPROVE, 30_000L, DAY.plusHours(11));

        assertThat(entriesOf(DAY, DAY.plusDays(1)))
                .singleElement()
                .satisfies(e -> assertThat(e.occurredAt()).isEqualTo(DAY.plusHours(10)));
    }

    @Test
    @DisplayName("같은 주문이라도 승인과 취소는 각각 기록된다")
    void approveAndCancelAreSeparate() {
        ledger.record("order-1", MockPgLedger.APPROVE, 30_000L, DAY.plusHours(10));
        ledger.record("order-1", MockPgLedger.CANCEL, 30_000L, DAY.plusHours(12));

        assertThat(entriesOf(DAY, DAY.plusDays(1)))
                .extracting(MockPgLedger.Entry::eventType)
                .containsExactly(MockPgLedger.APPROVE, MockPgLedger.CANCEL);
    }

    @Test
    @DisplayName("조회는 [from, to) 범위만 발생 순서대로 돌려준다 — 자정 정각은 다음 날")
    void forEachBetweenIsHalfOpen() {
        ledger.record("before", MockPgLedger.APPROVE, 1L, DAY.minusNanos(1_000));
        ledger.record("start", MockPgLedger.APPROVE, 2L, DAY);
        ledger.record("last", MockPgLedger.APPROVE, 3L, DAY.plusDays(1).minusNanos(1_000));
        ledger.record("next-day", MockPgLedger.APPROVE, 4L, DAY.plusDays(1));

        assertThat(entriesOf(DAY, DAY.plusDays(1)))
                .extracting(MockPgLedger.Entry::orderId)
                .containsExactly("start", "last");
    }

    @Test
    @DisplayName("바깥 트랜잭션이 롤백돼도 원장 기록은 남는다 (외부 PG 흉내)")
    void recordSurvivesOuterRollback() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            ledger.record("order-rollback", MockPgLedger.APPROVE, 30_000L, DAY.plusHours(1));
            tx.setRollbackOnly();
        });

        assertThat(entriesOf(DAY, DAY.plusDays(1)))
                .extracting(MockPgLedger.Entry::orderId)
                .containsExactly("order-rollback");
    }

    private List<MockPgLedger.Entry> entriesOf(LocalDateTime from, LocalDateTime to) {
        List<MockPgLedger.Entry> entries = new ArrayList<>();
        ledger.forEachBetween(from, to, entries::add);
        return entries;
    }
}
