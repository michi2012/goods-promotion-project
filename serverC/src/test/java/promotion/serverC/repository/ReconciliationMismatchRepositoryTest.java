package promotion.serverC.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import promotion.serverC.batch.Mismatch;
import promotion.serverC.batch.MismatchType;
import promotion.serverC.support.AbstractRepositoryTest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReconciliationMismatchRepository.class)
class ReconciliationMismatchRepositoryTest extends AbstractRepositoryTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 16);

    @Autowired
    private ReconciliationMismatchRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM reconciliation_mismatches");
    }

    @Test
    @DisplayName("같은 불일치를 다시 써도 한 벌만 남고, 유형별 건수를 센다")
    void insertIgnore_countByType() {
        Mismatch amount = new Mismatch(D, "order-1", "APPROVE", MismatchType.AMOUNT, 30_000L, 29_000L);

        repository.insertIgnore(List.of(amount));
        repository.insertIgnore(List.of(amount,
                new Mismatch(D, "order-2", "APPROVE", MismatchType.OURS_ONLY, 10_000L, null),
                new Mismatch(D, "order-3", "CANCEL", MismatchType.OURS_ONLY, 10_000L, null)));

        assertThat(repository.countByType(D)).isEqualTo(Map.of(
                MismatchType.AMOUNT, 1L,
                MismatchType.OURS_ONLY, 2L));
    }

    @Test
    @DisplayName("대사일 단위로 지운다 — 다른 날짜는 남는다")
    void deleteByReconDate() {
        repository.insertIgnore(List.of(
                new Mismatch(D, "order-1", "APPROVE", MismatchType.PG_ONLY, null, 1L),
                new Mismatch(D.plusDays(1), "order-2", "APPROVE", MismatchType.STATUS, 1L, 1L)));

        assertThat(repository.deleteByReconDate(D)).isEqualTo(1);
        assertThat(repository.countByType(D)).isEmpty();
        assertThat(repository.countByType(D.plusDays(1))).isEqualTo(Map.of(MismatchType.STATUS, 1L));
    }
}
