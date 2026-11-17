package promotion.serverC.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재현 ① 컷오프 (명세 S-2·S-8, P-3·P-7).
 * D에 승인되고 D+1 새벽(대사 전)에 취소된 결제: "현재 상태"로 비교하면 실행 시점마다 결과가 달라지고 오탐이 난다.
 * Job은 이벤트 시각 [D, D+1)로 비교하므로 언제 다시 실행해도 같은 결과, 날짜당 한 벌이다.
 */
class ReconciliationCutoffReproTest extends ReconciliationJobTestSupport {

    @Test
    @DisplayName("단순 구현(현재 상태 비교): D+1 새벽 취소 전후로 같은 D의 결과가 달라진다 — 오탐")
    void naive_현재상태비교는_실행시점마다_다르다() {
        seed();

        List<String> beforeCancel = naiveCurrentStateMismatches(D);
        cancel("o-late-cancel", D.plusDays(1).atTime(1, 0));
        List<String> afterCancel = naiveCurrentStateMismatches(D);

        assertThat(beforeCancel).isEmpty();
        assertThat(afterCancel).containsExactly("o-late-cancel:CANCEL"); // D 파일에 취소가 없다고 오탐
    }

    @Test
    @DisplayName("Job(컷오프 비교): D+1 취소 전후로 다시 실행해도 D 결과가 같고, 이전 결과는 교체돼 한 벌만 남는다")
    void job_재실행해도_결과동일_한벌() throws Exception {
        seed();
        payment("o-ours", "PAID", 5_000L, D.atTime(15, 0, 1), null); // 실제 불일치 1건 (결과 동일 확인용)

        assertThat(jobLauncherTestUtils.launchJob(params(D)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Mismatch> first = mismatches(D);

        cancel("o-late-cancel", D.plusDays(1).atTime(1, 0));
        // 재실행 전에 남아 있던 이전 결과가 교체되는지 보기 위한 가짜 행
        jdbcTemplate.update(
                "INSERT INTO reconciliation_mismatches (recon_date, order_id, event_type, mismatch_type, created_at) " +
                "VALUES (?, 'stale', 'APPROVE', 'PG_ONLY', NOW(6))", D);

        assertThat(jobLauncherTestUtils.launchJob(rerunParams(D)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Mismatch> second = mismatches(D);

        assertThat(first).containsExactly(new Mismatch(D, "o-ours", "APPROVE", MismatchType.OURS_ONLY, 5_000L, null));
        assertThat(second).isEqualTo(first);
    }

    // D 10:00 승인, 대사 시점(D+1 03:00) 전에 취소될 결제. PG D 파일에는 승인만 있다.
    private void seed() {
        payment("o-late-cancel", "PAID", 10_000L, D.atTime(10, 0, 1), null);
        pgFile(D, approve("o-late-cancel", 10_000L, D.atTime(10, 0)));
        pgFile(D.minusDays(1));
    }

    private static JobParameters rerunParams(LocalDate reconDate) {
        return new JobParametersBuilder(params(reconDate))
                .addLocalDateTime("requestedAt", LocalDateTime.of(2026, 11, 17, 4, 0))
                .toJobParameters();
    }

    /**
     * 비교 대상 단순 구현: D에 승인된 결제의 "현재 상태"로 기대 PG 이벤트를 만든다
     * (PAID → 승인, CANCELLED → 승인 + 취소). D 파일(seed와 같은 내용)에 없는 이벤트를 불일치로 본다.
     */
    private List<String> naiveCurrentStateMismatches(LocalDate reconDate) {
        Set<String> pgEventsInDayFile = Set.of("o-late-cancel:APPROVE");
        List<String> mismatches = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT order_id, status FROM payments WHERE approved_at >= ? AND approved_at < ? ORDER BY order_id",
                rs -> {
                    String orderId = rs.getString("order_id");
                    List<String> expected = "CANCELLED".equals(rs.getString("status"))
                            ? List.of(orderId + ":APPROVE", orderId + ":CANCEL")
                            : List.of(orderId + ":APPROVE");
                    expected.stream().filter(e -> !pgEventsInDayFile.contains(e)).forEach(mismatches::add);
                },
                reconDate.atStartOfDay(), reconDate.plusDays(1).atStartOfDay());
        return mismatches;
    }
}
