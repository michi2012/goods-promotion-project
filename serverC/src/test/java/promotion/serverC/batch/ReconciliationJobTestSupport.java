package promotion.serverC.batch;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import promotion.serverC.repository.PaymentRepository;
import promotion.serverC.repository.PgSettlementLineRepository;
import promotion.serverC.repository.ReconciliationMismatchRepository;
import promotion.serverC.repository.ReconciliationRunRepository;
import promotion.serverC.scheduler.MockPgFileExporter;
import promotion.serverC.support.AbstractRepositoryTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 대사 Job 테스트 공통: 실제 MySQL(Flyway 스키마 + Batch 메타 테이블)과 임시 PG 파일 디렉터리.
 * Job은 Step마다 자기 트랜잭션을 커밋하므로 테스트 트랜잭션은 쓰지 않는다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(BatchAutoConfiguration.class)
@Import({ReconciliationJobConfig.class, PaymentRepository.class, PgSettlementLineRepository.class,
        ReconciliationMismatchRepository.class, ReconciliationRunRepository.class, ReconciliationMetrics.class,
        ReconciliationJobTestSupport.BatchTestUtils.class})
abstract class ReconciliationJobTestSupport extends AbstractRepositoryTest {

    // @SpringBatchTest는 JobExecution을 돌려주는 테스트 메서드(run 헬퍼)를 스코프용 팩토리로 오인해 직접 등록한다.
    @TestConfiguration
    static class BatchTestUtils {
        @Bean
        JobLauncherTestUtils jobLauncherTestUtils(Job pgReconciliationJob, JobLauncher jobLauncher,
                                                  JobRepository jobRepository) {
            JobLauncherTestUtils utils = new JobLauncherTestUtils();
            utils.setJob(pgReconciliationJob);
            utils.setJobLauncher(jobLauncher);
            utils.setJobRepository(jobRepository);
            return utils;
        }

        @Bean
        JobRepositoryTestUtils jobRepositoryTestUtils(JobRepository jobRepository) {
            return new JobRepositoryTestUtils(jobRepository);
        }

        // Job 리스너(ReconciliationMetrics)용 — @JdbcTest에는 메트릭 자동 설정이 없다
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    protected static final LocalDate D = LocalDate.of(2026, 11, 16);
    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private static final Path FILE_DIR = createTempDir();

    @DynamicPropertySource
    static void reconProps(DynamicPropertyRegistry registry) {
        registry.add("mock-pg.file-dir", FILE_DIR::toString);
        registry.add("reconciliation.chunk-size", () -> 2); // 여러 chunk로 나뉘게
    }

    @Autowired
    protected JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    protected JobRepositoryTestUtils jobRepositoryTestUtils;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanAll() throws IOException {
        jobRepositoryTestUtils.removeJobExecutions();
        jdbcTemplate.update("DELETE FROM reconciliation_mismatches");
        jdbcTemplate.update("DELETE FROM pg_settlement_lines");
        jdbcTemplate.update("DELETE FROM payments");
        try (var files = Files.list(FILE_DIR)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    protected JobExecution run(LocalDate reconDate) throws Exception {
        return jobLauncherTestUtils.launchJob(params(reconDate));
    }

    protected static JobParameters params(LocalDate reconDate) {
        return new JobParametersBuilder()
                .addLocalDate(ReconciliationJobConfig.RECON_DATE, reconDate)
                .toJobParameters();
    }

    // ---- 시드 ----

    protected void payment(String orderId, String status, Long amount, LocalDateTime approvedAt,
                           LocalDateTime cancelledAt) {
        jdbcTemplate.update(
                "INSERT INTO payments (order_id, user_id, goods_id, quantity, amount, payment_method, shipping_address, " +
                "zip_code, phone_number, email, status, created_at, approved_at, cancelled_at) " +
                "VALUES (?, 1, 1, 1, ?, 'CARD', '주소', '01234', '010-0000-0000', 'a@a.com', ?, NOW(6), ?, ?)",
                orderId, amount, status, approvedAt, cancelledAt);
    }

    protected void cancel(String orderId, LocalDateTime cancelledAt) {
        jdbcTemplate.update("UPDATE payments SET status = 'CANCELLED', cancelled_at = ? WHERE order_id = ?",
                cancelledAt, orderId);
    }

    protected static PgLine approve(String orderId, Long amount, LocalDateTime at) {
        return new PgLine(orderId, "APPROVE", amount, at);
    }

    protected static PgLine cancelLine(String orderId, Long amount, LocalDateTime at) {
        return new PgLine(orderId, "CANCEL", amount, at);
    }

    // MockPgFileExporter와 같은 형식으로 그날 PG 파일을 쓴다.
    protected void pgFile(LocalDate date, PgLine... lines) {
        List<String> rows = new ArrayList<>();
        rows.add("order_id,event_type,amount,occurred_at");
        for (PgLine line : lines) {
            rows.add(String.join(",", line.orderId(), line.eventType(),
                    line.amount() == null ? "" : line.amount().toString(), line.occurredAt().format(OCCURRED_AT)));
        }
        try {
            Files.write(FILE_DIR.resolve(MockPgFileExporter.fileName(date)), rows, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 결과 ----

    protected List<Mismatch> mismatches(LocalDate reconDate) {
        return jdbcTemplate.query(
                "SELECT recon_date, order_id, event_type, mismatch_type, our_amount, pg_amount " +
                "FROM reconciliation_mismatches WHERE recon_date = ? ORDER BY order_id, event_type",
                (rs, rowNum) -> new Mismatch(
                        rs.getObject("recon_date", LocalDate.class),
                        rs.getString("order_id"),
                        rs.getString("event_type"),
                        MismatchType.valueOf(rs.getString("mismatch_type")),
                        rs.getObject("our_amount", Long.class),
                        rs.getObject("pg_amount", Long.class)),
                reconDate);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("pg-files-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
