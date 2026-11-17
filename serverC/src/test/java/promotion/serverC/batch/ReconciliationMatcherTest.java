package promotion.serverC.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import promotion.serverC.repository.PaymentRepository.PaymentEventTimes;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationMatcherTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 16);
    private static final ReconciliationMatcher MATCHER = new ReconciliationMatcher(D, Duration.ofMinutes(5));

    private static OurEvent ours(String type, Long amount, LocalDateTime at) {
        return new OurEvent("order-1", type, amount, at);
    }

    private static PgLine pg(String type, Long amount, LocalDateTime at) {
        return new PgLine("order-1", type, amount, at);
    }

    @Test
    @DisplayName("우리→PG: 같은 날 같은 금액이면 일치")
    void oursToPg_일치() {
        assertThat(MATCHER.oursToPg(ours("APPROVE", 30_000L, D.atTime(10, 0, 1)),
                Optional.of(pg("APPROVE", 30_000L, D.atTime(10, 0))))).isNull();
    }

    @Test
    @DisplayName("우리→PG: 금액이 다르면 ③, 한쪽 금액이 없으면 비교하지 않는다")
    void oursToPg_금액() {
        assertThat(MATCHER.oursToPg(ours("APPROVE", 30_000L, D.atTime(10, 0)),
                Optional.of(pg("APPROVE", 29_000L, D.atTime(10, 0)))))
                .isEqualTo(new Mismatch(D, "order-1", "APPROVE", MismatchType.AMOUNT, 30_000L, 29_000L));
        assertThat(MATCHER.oursToPg(ours("CANCEL", null, D.atTime(10, 0)),
                Optional.of(pg("CANCEL", 30_000L, D.atTime(10, 0))))).isNull();
    }

    @Test
    @DisplayName("우리→PG: PG 줄이 없으면 ①")
    void oursToPg_우리만() {
        assertThat(MATCHER.oursToPg(ours("CANCEL", 30_000L, D.atTime(10, 0)), Optional.empty()))
                .isEqualTo(new Mismatch(D, "order-1", "CANCEL", MismatchType.OURS_ONLY, 30_000L, null));
    }

    @Test
    @DisplayName("우리→PG: D-1 꼬리 줄과 허용치(5분) 이내면 경계 건으로 제외, 넘으면 ①")
    void oursToPg_경계() {
        PgLine prevTail = pg("APPROVE", 30_000L, D.minusDays(1).atTime(23, 59, 59));

        assertThat(MATCHER.oursToPg(ours("APPROVE", 30_000L, D.atTime(0, 0, 1)), Optional.of(prevTail))).isNull();
        assertThat(MATCHER.oursToPg(ours("APPROVE", 30_000L, D.atTime(0, 5, 0)), Optional.of(prevTail)))
                .extracting(Mismatch::type).isEqualTo(MismatchType.OURS_ONLY);
    }

    @Test
    @DisplayName("PG→우리: 결제 건이 없으면 ②")
    void pgToOurs_PG만() {
        assertThat(MATCHER.pgToOurs(pg("APPROVE", 30_000L, D.atTime(10, 0)), Optional.empty()))
                .isEqualTo(new Mismatch(D, "order-1", "APPROVE", MismatchType.PG_ONLY, null, 30_000L));
    }

    @Test
    @DisplayName("PG→우리: 같은 날 우리 이벤트가 있으면 일치 (금액은 우리→PG 쪽이 비교)")
    void pgToOurs_일치() {
        PaymentEventTimes payment = new PaymentEventTimes("order-1", 1L, D.atTime(10, 0, 1), null);

        assertThat(MATCHER.pgToOurs(pg("APPROVE", 30_000L, D.atTime(10, 0)), Optional.of(payment))).isNull();
    }

    @Test
    @DisplayName("PG→우리: 결제 건은 있으나 그날 그 이벤트가 없으면 ④ (PG 승인, 우리 FAILED)")
    void pgToOurs_상태() {
        PaymentEventTimes failed = new PaymentEventTimes("order-1", 30_000L, null, null);

        assertThat(MATCHER.pgToOurs(pg("APPROVE", 30_000L, D.atTime(10, 0)), Optional.of(failed)))
                .isEqualTo(new Mismatch(D, "order-1", "APPROVE", MismatchType.STATUS, 30_000L, 30_000L));
    }

    @Test
    @DisplayName("PG→우리: 우리 이벤트가 D+1이어도 허용치 이내면 경계 건으로 제외, 넘으면 ④")
    void pgToOurs_경계() {
        PgLine lastSecond = pg("CANCEL", 30_000L, D.atTime(23, 59, 59));

        assertThat(MATCHER.pgToOurs(lastSecond, Optional.of(
                new PaymentEventTimes("order-1", 30_000L, D.atTime(9, 0), D.plusDays(1).atTime(0, 0, 1))))).isNull();
        assertThat(MATCHER.pgToOurs(lastSecond, Optional.of(
                new PaymentEventTimes("order-1", 30_000L, D.atTime(9, 0), D.plusDays(1).atTime(3, 0)))))
                .extracting(Mismatch::type).isEqualTo(MismatchType.STATUS);
    }
}
