package promotion.serverC.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import promotion.serverC.service.MockPgLedger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MockPgFileExporterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 16);

    @TempDir
    Path dir;

    private MockPgLedger ledger;
    private MockPgFileExporter exporter;

    @BeforeEach
    void setUp() {
        ledger = mock(MockPgLedger.class);
        exporter = new MockPgFileExporter(ledger, dir.toString());
    }

    @Test
    @DisplayName("그날 원장을 헤더 + 한 줄에 이벤트 1건으로 쓴다 — 금액 없으면 빈 칸, 시각은 자릿수 고정")
    void exportWritesCsv() throws IOException {
        givenEntries(
                new MockPgLedger.Entry("order-1", MockPgLedger.APPROVE, 30_000L, DATE.atTime(10, 0)),
                new MockPgLedger.Entry("order-1", MockPgLedger.CANCEL, null, DATE.atTime(23, 59, 59, 123_456_000)));

        Path file = exporter.export(DATE);

        assertThat(file.getFileName().toString()).isEqualTo("pg-transactions-20261116.csv");
        assertThat(Files.readAllLines(file)).containsExactly(
                "order_id,event_type,amount,occurred_at",
                "order-1,APPROVE,30000,2026-11-16T10:00:00.000000",
                "order-1,CANCEL,,2026-11-16T23:59:59.123456");
    }

    @Test
    @DisplayName("대사일 하루 [D 00:00, D+1 00:00) 범위로 원장을 조회한다")
    void exportQueriesOneDay() {
        givenEntries();

        exporter.export(DATE);

        verify(ledger).forEachBetween(
                eq(LocalDateTime.of(2026, 11, 16, 0, 0)), eq(LocalDateTime.of(2026, 11, 17, 0, 0)), any());
    }

    @Test
    @DisplayName("거래가 없는 날도 헤더만 있는 파일을 만든다 (파일 없음 = 생성 실패와 구분)")
    void exportEmptyDay() throws IOException {
        givenEntries();

        Path file = exporter.export(DATE);

        assertThat(Files.readAllLines(file)).containsExactly(MockPgFileExporter.HEADER);
    }

    @Test
    @DisplayName("같은 날짜를 다시 만들면 기존 파일을 교체하고 임시 파일을 남기지 않는다")
    void exportReplacesExistingFile() throws IOException {
        givenEntries(new MockPgLedger.Entry("old", MockPgLedger.APPROVE, 1L, DATE.atTime(1, 0)));
        exporter.export(DATE);
        givenEntries(new MockPgLedger.Entry("new", MockPgLedger.APPROVE, 2L, DATE.atTime(2, 0)));

        Path file = exporter.export(DATE);

        assertThat(Files.readAllLines(file)).containsExactly(
                MockPgFileExporter.HEADER, "new,APPROVE,2,2026-11-16T02:00:00.000000");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("pg-transactions-20261116.csv");
        }
    }

    @SuppressWarnings("unchecked")
    private void givenEntries(MockPgLedger.Entry... entries) {
        doAnswer(inv -> {
            Consumer<MockPgLedger.Entry> consumer = inv.getArgument(2);
            List.of(entries).forEach(consumer);
            return null;
        }).when(ledger).forEachBetween(any(), any(), any());
    }
}
