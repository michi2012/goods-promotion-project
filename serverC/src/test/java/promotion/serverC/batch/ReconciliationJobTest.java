package promotion.serverC.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사 Job 판정 (명세 S-1, S-3~S-7, S-10). 실제 MySQL + PG 파일.
 */
class ReconciliationJobTest extends ReconciliationJobTestSupport {

    @Test
    @DisplayName("S-1: D의 승인·취소가 PG 파일과 모두 일치하면 불일치 0건으로 완료")
    void s1_모두일치() throws Exception {
        payment("o-1", "PAID", 10_000L, D.atTime(9, 0, 1), null);
        payment("o-2", "CANCELLED", 20_000L, D.atTime(10, 0, 1), D.atTime(11, 0, 1));
        payment("o-3", "PAID", 30_000L, D.atTime(12, 0, 1), null);
        pgFile(D,
                approve("o-1", 10_000L, D.atTime(9, 0)),
                approve("o-2", 20_000L, D.atTime(10, 0)),
                cancelLine("o-2", 20_000L, D.atTime(11, 0)),
                approve("o-3", 30_000L, D.atTime(12, 0)));
        pgFile(D.minusDays(1));

        JobExecution execution = run(D);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(mismatches(D)).isEmpty();
        assertThat(lineCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("S-3~S-6: 상태·PG만·우리만·금액 불일치를 각각 기록한다")
    void s3to6_네가지불일치() throws Exception {
        payment("o-status", "FAILED", 10_000L, null, null);               // S-3 PG 승인, 우리 FAILED
        payment("o-ours", "PAID", 20_000L, D.atTime(10, 0, 1), null);     // S-5 PG에 없음
        payment("o-amount", "PAID", 30_000L, D.atTime(11, 0, 1), null);   // S-6 금액 다름
        pgFile(D,
                approve("o-status", 10_000L, D.atTime(9, 0)),
                approve("o-pg", 40_000L, D.atTime(9, 30)),                 // S-4 우리 DB에 없음
                approve("o-amount", 29_000L, D.atTime(11, 0)));

        JobExecution execution = run(D);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(mismatches(D)).containsExactly(
                new Mismatch(D, "o-amount", "APPROVE", MismatchType.AMOUNT, 30_000L, 29_000L),
                new Mismatch(D, "o-ours", "APPROVE", MismatchType.OURS_ONLY, 20_000L, null),
                new Mismatch(D, "o-pg", "APPROVE", MismatchType.PG_ONLY, null, 40_000L),
                new Mismatch(D, "o-status", "APPROVE", MismatchType.STATUS, 10_000L, 10_000L));
    }

    @Test
    @DisplayName("S-7: PG D 23:59:59 승인 / 우리 D+1 00:00:01 승인은 D·D+1 대사 모두에서 제외")
    void s7_날짜경계() throws Exception {
        payment("o-edge", "PAID", 10_000L, D.plusDays(1).atTime(0, 0, 1), null);
        pgFile(D, approve("o-edge", 10_000L, D.atTime(23, 59, 59)));
        pgFile(D.plusDays(1));

        assertThat(run(D).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run(D.plusDays(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mismatches(D)).isEmpty();
        assertThat(mismatches(D.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("D-1 파일은 마지막 허용치(5분) 구간 줄만 적재하고, Step 4 비교 대상에서는 빠진다")
    void 이전파일_꼬리만적재() throws Exception {
        pgFile(D.minusDays(1),
                approve("o-old", 10_000L, D.minusDays(1).atTime(23, 54, 59)),
                approve("o-tail", 10_000L, D.minusDays(1).atTime(23, 55, 0)));
        pgFile(D);

        JobExecution execution = run(D);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForList(
                "SELECT order_id FROM pg_settlement_lines WHERE recon_date = ?", String.class, D))
                .containsExactly("o-tail");
        assertThat(mismatches(D)).isEmpty();
    }

    @Test
    @DisplayName("S-10: D 파일이 없으면 적재 Step이 실패해 Job FAILED")
    void s10_파일없음_실패() throws Exception {
        pgFile(D.minusDays(1));

        JobExecution execution = run(D);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).filteredOn(s -> s.getStatus() == BatchStatus.FAILED)
                .extracting(StepExecution::getStepName).containsExactly("reconLoadDayStep");
    }

    @Test
    @DisplayName("D-1 파일이 없어도 경고만 하고 진행한다 (대사 첫날)")
    void 이전파일없음_진행() throws Exception {
        payment("o-1", "PAID", 10_000L, D.atTime(9, 0, 1), null);
        pgFile(D, approve("o-1", 10_000L, D.atTime(9, 0)));

        JobExecution execution = run(D);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(mismatches(D)).isEmpty();
    }

    private int lineCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_settlement_lines WHERE recon_date = ?", Integer.class, D);
    }
}
