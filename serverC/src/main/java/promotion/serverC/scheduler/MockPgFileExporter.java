package promotion.serverC.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import promotion.serverC.service.MockPgLedger;
import promotion.serverC.service.ReconciliationTime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 모의 PG가 매일 주는 거래 파일을 만든다 (실제 PG의 일자별 거래 대사 파일 흉내).
 * 파일 한 줄 = 거래 이벤트 1건: order_id,event_type,amount,occurred_at
 * 대사 배치가 반쯤 쓴 파일을 읽지 않도록 임시 파일에 다 쓴 뒤 이름을 바꾼다. 같은 날짜를 다시 만들면 교체한다.
 */
@Component
@Slf4j
public class MockPgFileExporter {

    static final String HEADER = "order_id,event_type,amount,occurred_at";
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    // 초·소수점이 0이어도 자릿수를 고정한다 (LocalDateTime.toString은 "10:00"처럼 줄여 쓴다).
    private static final DateTimeFormatter OCCURRED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    private final MockPgLedger ledger;
    private final Path fileDir;

    public MockPgFileExporter(MockPgLedger ledger, @Value("${mock-pg.file-dir:./pg-files}") String fileDir) {
        this.ledger = ledger;
        this.fileDir = Path.of(fileDir);
    }

    @Scheduled(cron = "${mock-pg.export.cron:0 10 0 * * *}", zone = "Asia/Seoul")
    public void exportYesterday() {
        LocalDate yesterday = LocalDate.now(ReconciliationTime.ZONE).minusDays(1);
        try {
            Path file = export(yesterday);
            log.info("[MockPgFile] 거래 파일 생성: {}", file);
        } catch (RuntimeException e) {
            // 파일이 없으면 그날 대사가 실패하고 알람이 난다 (명세 S-10)
            log.error("🚨 [MockPgFile] 거래 파일 생성 실패: date={}", yesterday, e);
        }
    }

    // 대사 배치도 같은 이름으로 파일을 찾는다.
    public static String fileName(LocalDate date) {
        return "pg-transactions-" + date.format(FILE_DATE) + ".csv";
    }

    public Path export(LocalDate date) {
        Path target = fileDir.resolve(fileName(date));
        Path temp = fileDir.resolve(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(fileDir);
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.newLine();
                ledger.forEachBetween(date.atStartOfDay(), date.plusDays(1).atStartOfDay(),
                        entry -> writeLine(writer, entry));
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeLine(BufferedWriter writer, MockPgLedger.Entry entry) {
        try {
            writer.write(String.join(",",
                    entry.orderId(),
                    entry.eventType(),
                    entry.amount() == null ? "" : entry.amount().toString(),
                    entry.occurredAt().format(OCCURRED_AT)));
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
