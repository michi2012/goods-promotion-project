package promotion.serverC.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import promotion.serverC.batch.PgLine;
import promotion.serverC.support.AbstractRepositoryTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PgSettlementLineRepository.class)
class PgSettlementLineRepositoryTest extends AbstractRepositoryTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 16);

    @Autowired
    private PgSettlementLineRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM pg_settlement_lines");
    }

    @Test
    @DisplayName("같은 줄을 다시 적재해도 한 벌만 남는다 (재시도·재시작)")
    void insertIgnore_중복무시() {
        PgLine line = new PgLine("order-1", "APPROVE", 30_000L, D.atTime(10, 0));

        repository.insertIgnore(D, List.of(line));
        repository.insertIgnore(D, List.of(line, new PgLine("order-1", "CANCEL", null, D.atTime(11, 0))));

        assertThat(count(D)).isEqualTo(2);
    }

    @Test
    @DisplayName("중복이 아닌 데이터 오류(order_id 길이 초과)는 삼키지 않고 예외로 드러낸다 — 대사 대상이 조용히 빠지지 않게")
    void insertIgnore_데이터오류는_예외() {
        PgLine tooLong = new PgLine("o".repeat(256), "APPROVE", 1L, D.atTime(10, 0));

        assertThatThrownBy(() -> repository.insertIgnore(D, List.of(tooLong)))
                .isInstanceOf(DataAccessException.class);
        assertThat(count(D)).isZero();
    }

    @Test
    @DisplayName("대사일·주문·이벤트로 줄을 찾는다 — 금액 없는 줄은 null")
    void find() {
        LocalDateTime at = D.atTime(23, 59, 59, 123_456_000);
        repository.insertIgnore(D, List.of(new PgLine("order-1", "CANCEL", null, at)));

        assertThat(repository.find(D, "order-1", "CANCEL"))
                .contains(new PgLine("order-1", "CANCEL", null, at));
        assertThat(repository.find(D, "order-1", "APPROVE")).isEmpty();
        assertThat(repository.find(D.plusDays(1), "order-1", "CANCEL")).isEmpty();
    }

    @Test
    @DisplayName("대사일 단위로 지운다 — 다른 날짜는 남는다")
    void deleteByReconDate() {
        repository.insertIgnore(D, List.of(new PgLine("order-1", "APPROVE", 1L, D.atTime(1, 0))));
        repository.insertIgnore(D.plusDays(1), List.of(new PgLine("order-2", "APPROVE", 1L, D.atTime(2, 0))));

        assertThat(repository.deleteByReconDate(D)).isEqualTo(1);
        assertThat(count(D)).isZero();
        assertThat(count(D.plusDays(1))).isEqualTo(1);
    }

    private int count(LocalDate reconDate) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_settlement_lines WHERE recon_date = ?", Integer.class, reconDate);
    }
}
