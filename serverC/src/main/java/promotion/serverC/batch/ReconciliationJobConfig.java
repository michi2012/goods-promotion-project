package promotion.serverC.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;
import promotion.serverC.repository.PaymentRepository;
import promotion.serverC.repository.PgSettlementLineRepository;
import promotion.serverC.repository.ReconciliationMismatchRepository;
import promotion.serverC.scheduler.MockPgFileExporter;
import promotion.serverC.service.MockPgLedger;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PG 거래 내역 대사 Job (docs/specs/pg-reconciliation-batch.md "배치 Job").
 * 파라미터 reconDate(LocalDate). 같은 파라미터로 다시 실행하면 실패한 Step의 마지막 커밋 위치부터 재시작한다.
 *
 * clear → loadPrevTail → loadDay → oursApprove → oursCancel → pgToOurs → report
 * - 비교 대상은 이벤트 시각으로만 고른다 (컷오프 P-3, 처리 중 상태 변경에 흔들리지 않음 S-11).
 * - DB 조회는 키셋 페이징(JdbcPagingItemReader), 쓰기는 INSERT IGNORE라 chunk 재시도·재시작에도 한 벌 (S-9).
 */
@Configuration
@Slf4j
public class ReconciliationJobConfig {

    public static final String JOB_NAME = "pgReconciliationJob";
    public static final String RECON_DATE = "reconDate";

    private static final String[] CSV_COLUMNS = {"order_id", "event_type", "amount", "occurred_at"};

    private final int chunkSize;

    public ReconciliationJobConfig(@Value("${reconciliation.chunk-size:500}") int chunkSize) {
        this.chunkSize = chunkSize;
    }

    @Bean
    public Job pgReconciliationJob(JobRepository jobRepository, Step reconClearStep, Step reconLoadPrevTailStep,
                                   Step reconLoadDayStep, Step reconOursApproveStep, Step reconOursCancelStep,
                                   Step reconPgToOursStep, Step reconReportStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(reconClearStep)
                .next(reconLoadPrevTailStep)
                .next(reconLoadDayStep)
                .next(reconOursApproveStep)
                .next(reconOursCancelStep)
                .next(reconPgToOursStep)
                .next(reconReportStep)
                .build();
    }

    // Step 1: 그 날짜의 이전 결과를 지운다 (P-7 교체). 재시작 시 완료된 Step은 다시 돌지 않는다.
    @Bean
    public Step reconClearStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                               ReconciliationMismatchRepository mismatchRepository,
                               PgSettlementLineRepository lineRepository) {
        return new StepBuilder("reconClearStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    LocalDate reconDate = reconDate(chunkContext.getStepContext().getJobParameters());
                    int mismatches = mismatchRepository.deleteByReconDate(reconDate);
                    int lines = lineRepository.deleteByReconDate(reconDate);
                    log.info("[Reconciliation] {} 이전 결과 삭제: 불일치 {}건, PG 줄 {}건", reconDate, mismatches, lines);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    // Step 2a: D-1 파일의 마지막 허용치 구간 줄 — D의 "우리만" 후보를 경계 건인지 확인하는 데만 쓴다.
    // 파일이 없어도 진행한다 (경계 확인용일 뿐이고, 대사 시작 첫날엔 원래 없다).
    @Bean
    public Step reconLoadPrevTailStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                      FlatFileItemReader<PgLine> reconPrevFileReader,
                                      ItemProcessor<PgLine, PgLine> reconPrevTailFilter,
                                      ItemWriter<PgLine> reconLineWriter) {
        return new StepBuilder("reconLoadPrevTailStep", jobRepository)
                .<PgLine, PgLine>chunk(chunkSize, transactionManager)
                .reader(reconPrevFileReader)
                .processor(reconPrevTailFilter)
                .writer(reconLineWriter)
                .build();
    }

    // Step 2b: D 파일 전체. 파일이 없으면 Step 실패 → Job FAILED (S-10). 읽은 줄 수가 저장돼 재시작 시 이어 읽는다.
    @Bean
    public Step reconLoadDayStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                 FlatFileItemReader<PgLine> reconDayFileReader,
                                 ItemWriter<PgLine> reconLineWriter) {
        return new StepBuilder("reconLoadDayStep", jobRepository)
                .<PgLine, PgLine>chunk(chunkSize, transactionManager)
                .reader(reconDayFileReader)
                .writer(reconLineWriter)
                .build();
    }

    // Step 3a·3b: D의 우리 승인·취소 이벤트 → PG (①③). 정렬 키가 달라 이벤트 종류별로 Step을 나눈다.
    @Bean
    public Step reconOursApproveStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                     JdbcPagingItemReader<OurEvent> reconOursApproveReader,
                                     ItemProcessor<OurEvent, Mismatch> reconOursProcessor,
                                     ItemWriter<Mismatch> reconMismatchWriter) {
        return oursStep("reconOursApproveStep", jobRepository, transactionManager,
                reconOursApproveReader, reconOursProcessor, reconMismatchWriter);
    }

    @Bean
    public Step reconOursCancelStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                    JdbcPagingItemReader<OurEvent> reconOursCancelReader,
                                    ItemProcessor<OurEvent, Mismatch> reconOursProcessor,
                                    ItemWriter<Mismatch> reconMismatchWriter) {
        return oursStep("reconOursCancelStep", jobRepository, transactionManager,
                reconOursCancelReader, reconOursProcessor, reconMismatchWriter);
    }

    // Step 4: D 안의 PG 줄 → 우리 (②④). D-1 꼬리 줄은 비교 대상이 아니다.
    @Bean
    public Step reconPgToOursStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                  JdbcPagingItemReader<PgLine> reconPgLineReader,
                                  ItemProcessor<PgLine, Mismatch> reconPgProcessor,
                                  ItemWriter<Mismatch> reconMismatchWriter) {
        return new StepBuilder("reconPgToOursStep", jobRepository)
                .<PgLine, Mismatch>chunk(chunkSize, transactionManager)
                .reader(reconPgLineReader)
                .processor(reconPgProcessor)
                .writer(reconMismatchWriter)
                .build();
    }

    // Step 5: 유형별 건수 요약 (메트릭·알람은 BL-051).
    @Bean
    public Step reconReportStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                ReconciliationMismatchRepository mismatchRepository) {
        return new StepBuilder("reconReportStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    LocalDate reconDate = reconDate(chunkContext.getStepContext().getJobParameters());
                    Map<MismatchType, Long> counts = mismatchRepository.countByType(reconDate);
                    if (counts.isEmpty()) {
                        log.info("[Reconciliation] {} 대사 완료: 불일치 없음", reconDate);
                    } else {
                        log.warn("🚨 [Reconciliation] {} 대사 완료: 불일치 {}", reconDate, counts);
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    // ---- readers / processors / writers (Step 범위: 실행마다 reconDate로 새로 만든다) ----

    @Bean
    @StepScope
    public FlatFileItemReader<PgLine> reconPrevFileReader(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate,
            @Value("${mock-pg.file-dir:./pg-files}") String fileDir) {
        return fileReader("reconPrevFileReader", Path.of(fileDir), reconDate.minusDays(1), false);
    }

    @Bean
    @StepScope
    public FlatFileItemReader<PgLine> reconDayFileReader(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate,
            @Value("${mock-pg.file-dir:./pg-files}") String fileDir) {
        return fileReader("reconDayFileReader", Path.of(fileDir), reconDate, true);
    }

    @Bean
    @StepScope
    public ItemProcessor<PgLine, PgLine> reconPrevTailFilter(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate,
            @Value("${reconciliation.boundary-tolerance:5m}") Duration tolerance) {
        LocalDateTime tailStart = reconDate.atStartOfDay().minus(tolerance);
        return line -> line.occurredAt().isBefore(tailStart) ? null : line;
    }

    @Bean
    @StepScope
    public ItemWriter<PgLine> reconLineWriter(@Value("#{jobParameters['reconDate']}") LocalDate reconDate,
                                              PgSettlementLineRepository lineRepository) {
        return chunk -> lineRepository.insertIgnore(reconDate, chunk.getItems());
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<OurEvent> reconOursApproveReader(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate, DataSource dataSource) {
        return oursReader("reconOursApproveReader", dataSource, reconDate, "approved_at", MockPgLedger.APPROVE);
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<OurEvent> reconOursCancelReader(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate, DataSource dataSource) {
        return oursReader("reconOursCancelReader", dataSource, reconDate, "cancelled_at", MockPgLedger.CANCEL);
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<PgLine> reconPgLineReader(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate, DataSource dataSource) {
        return new JdbcPagingItemReaderBuilder<PgLine>()
                .name("reconPgLineReader")
                .dataSource(dataSource)
                .selectClause("SELECT id, order_id, event_type, amount, occurred_at")
                .fromClause("FROM pg_settlement_lines")
                .whereClause("WHERE recon_date = :reconDate AND occurred_at >= :from AND occurred_at < :to")
                .sortKeys(sortKeys("occurred_at"))
                .parameterValues(Map.of(
                        "reconDate", reconDate,
                        "from", reconDate.atStartOfDay(),
                        "to", reconDate.plusDays(1).atStartOfDay()))
                .pageSize(chunkSize)
                .rowMapper((rs, rowNum) -> new PgLine(
                        rs.getString("order_id"),
                        rs.getString("event_type"),
                        rs.getObject("amount", Long.class),
                        rs.getObject("occurred_at", LocalDateTime.class)))
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<OurEvent, Mismatch> reconOursProcessor(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate,
            @Value("${reconciliation.boundary-tolerance:5m}") Duration tolerance,
            PgSettlementLineRepository lineRepository) {
        ReconciliationMatcher matcher = new ReconciliationMatcher(reconDate, tolerance);
        return ours -> matcher.oursToPg(ours, lineRepository.find(reconDate, ours.orderId(), ours.eventType()));
    }

    @Bean
    @StepScope
    public ItemProcessor<PgLine, Mismatch> reconPgProcessor(
            @Value("#{jobParameters['reconDate']}") LocalDate reconDate,
            @Value("${reconciliation.boundary-tolerance:5m}") Duration tolerance,
            PaymentRepository paymentRepository) {
        ReconciliationMatcher matcher = new ReconciliationMatcher(reconDate, tolerance);
        return pg -> matcher.pgToOurs(pg, paymentRepository.findEventTimes(pg.orderId()));
    }

    @Bean
    public ItemWriter<Mismatch> reconMismatchWriter(ReconciliationMismatchRepository mismatchRepository) {
        return chunk -> mismatchRepository.insertIgnore(chunk.getItems());
    }

    // ---- helpers ----

    private Step oursStep(String name, JobRepository jobRepository, PlatformTransactionManager transactionManager,
                          JdbcPagingItemReader<OurEvent> reader, ItemProcessor<OurEvent, Mismatch> processor,
                          ItemWriter<Mismatch> writer) {
        return new StepBuilder(name, jobRepository)
                .<OurEvent, Mismatch>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    // 조회 조건은 이벤트 시각뿐 — 처리 중 상태(status)가 바뀌어도 대상이 흔들리지 않는다.
    private JdbcPagingItemReader<OurEvent> oursReader(String name, DataSource dataSource, LocalDate reconDate,
                                                      String timeColumn, String eventType) {
        return new JdbcPagingItemReaderBuilder<OurEvent>()
                .name(name)
                .dataSource(dataSource)
                .selectClause("SELECT id, order_id, amount, " + timeColumn)
                .fromClause("FROM payments")
                .whereClause("WHERE " + timeColumn + " >= :from AND " + timeColumn + " < :to")
                .sortKeys(sortKeys(timeColumn))
                .parameterValues(Map.of(
                        "from", reconDate.atStartOfDay(),
                        "to", reconDate.plusDays(1).atStartOfDay()))
                .pageSize(chunkSize)
                .rowMapper((rs, rowNum) -> new OurEvent(
                        rs.getString("order_id"),
                        eventType,
                        rs.getObject("amount", Long.class),
                        rs.getObject(timeColumn, LocalDateTime.class)))
                .build();
    }

    // 키셋 페이징: (시각, id) > (마지막 시각, 마지막 id). 마지막 키가 Step 실행 컨텍스트에 저장돼 재시작 시 이어 읽는다.
    private static Map<String, Order> sortKeys(String timeColumn) {
        Map<String, Order> keys = new LinkedHashMap<>();
        keys.put(timeColumn, Order.ASCENDING);
        keys.put("id", Order.ASCENDING);
        return keys;
    }

    private static FlatFileItemReader<PgLine> fileReader(String name, Path dir, LocalDate fileDate, boolean strict) {
        return new FlatFileItemReaderBuilder<PgLine>()
                .name(name)
                .resource(new FileSystemResource(dir.resolve(MockPgFileExporter.fileName(fileDate))))
                .strict(strict)
                .encoding("UTF-8")
                .linesToSkip(1)
                .delimited()
                .names(CSV_COLUMNS)
                .fieldSetMapper(PG_LINE_MAPPER)
                .build();
    }

    private static final FieldSetMapper<PgLine> PG_LINE_MAPPER = fs -> {
        String amount = fs.readString("amount");
        return new PgLine(
                fs.readString("order_id"),
                fs.readString("event_type"),
                amount == null || amount.isBlank() ? null : Long.valueOf(amount),
                LocalDateTime.parse(fs.readString("occurred_at")));
    };

    private static LocalDate reconDate(Map<String, Object> jobParameters) {
        return (LocalDate) jobParameters.get(RECON_DATE);
    }
}
