package promotion.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import promotion.serverC.batch.ReconciliationJobConfig;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 대사 Job 실행 기록 조회 (Spring Batch 메타 테이블 BATCH_*).
 * JobExplorer는 파라미터로 실행을 찾지 못한다 — COMPLETED 뒤 재실행은 requestedAt이 붙은 별개 인스턴스라
 * 날짜(reconDate 파라미터) 기준으로 묶어 찾는다. reconDate는 ISO 날짜 문자열(yyyy-MM-dd)로 저장된다.
 */
@Repository
@RequiredArgsConstructor
public class ReconciliationRunRepository {

    private static final String FROM_RECON_EXECUTIONS =
            "FROM BATCH_JOB_EXECUTION e " +
            "JOIN BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID " +
            "JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID " +
            "WHERE i.JOB_NAME = ? AND p.PARAMETER_NAME = ? ";

    private final JdbcTemplate jdbcTemplate;

    public record RunRecord(long jobExecutionId, BatchStatus status) {
    }

    // 그 날짜의 가장 최근 실행 (인스턴스 무관)
    public Optional<RunRecord> findLatest(LocalDate reconDate) {
        return jdbcTemplate.query(
                "SELECT e.JOB_EXECUTION_ID, e.STATUS " + FROM_RECON_EXECUTIONS +
                "AND p.PARAMETER_VALUE = ? ORDER BY e.JOB_EXECUTION_ID DESC LIMIT 1",
                (rs, rowNum) -> new RunRecord(rs.getLong(1), BatchStatus.valueOf(rs.getString(2))),
                ReconciliationJobConfig.JOB_NAME, ReconciliationJobConfig.RECON_DATE, reconDate.toString()
        ).stream().findFirst();
    }

    // COMPLETED 실행이 있는 가장 늦은 대사일
    public Optional<LocalDate> findLastCompletedDate() {
        String value = jdbcTemplate.queryForObject(
                "SELECT MAX(p.PARAMETER_VALUE) " + FROM_RECON_EXECUTIONS + "AND e.STATUS = 'COMPLETED'",
                String.class,
                ReconciliationJobConfig.JOB_NAME, ReconciliationJobConfig.RECON_DATE);
        return Optional.ofNullable(value).map(LocalDate::parse);
    }
}
