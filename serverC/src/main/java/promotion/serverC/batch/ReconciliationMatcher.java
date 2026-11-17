package promotion.serverC.batch;

import promotion.serverC.repository.PaymentRepository.PaymentEventTimes;
import promotion.serverC.service.MockPgLedger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 대사 판정 (명세 "대사 기준: 이벤트 단위 비교"). 판정 대상 날짜 D의 범위는 [D 00:00, D+1 00:00) KST.
 * 날짜 경계(P-5): 우리는 PG 응답을 받은 뒤 기록하므로 PG 시각 ≤ 우리 시각. 한쪽에만 있는 후보는
 * 상대편 같은 이벤트와의 시각 차이가 허용치 이내면 경계 건으로 보고 불일치에서 뺀다.
 */
public class ReconciliationMatcher {

    private final LocalDate reconDate;
    private final Duration tolerance;

    public ReconciliationMatcher(LocalDate reconDate, Duration tolerance) {
        this.reconDate = reconDate;
        this.tolerance = tolerance;
    }

    /**
     * 우리 이벤트(D 안) → PG. pgLine은 같은 대사일로 적재된 같은 (orderId, 이벤트) 줄 — D 파일 줄이거나 D-1 파일 꼬리 줄.
     * 불일치가 아니면 null.
     */
    public Mismatch oursToPg(OurEvent ours, Optional<PgLine> pgLine) {
        if (pgLine.isEmpty()) {
            return mismatch(ours.orderId(), ours.eventType(), MismatchType.OURS_ONLY, ours.amount(), null);
        }
        PgLine pg = pgLine.get();
        if (!inDay(pg.occurredAt())) {
            // D-1 꼬리 줄: 경계 건이면 제외, 아니면 그 PG 이벤트는 D-1 대사 몫이고 D에는 대응 PG 이벤트가 없다
            return withinTolerance(pg.occurredAt(), ours.eventAt())
                    ? null
                    : mismatch(ours.orderId(), ours.eventType(), MismatchType.OURS_ONLY, ours.amount(), null);
        }
        if (ours.amount() != null && pg.amount() != null && !Objects.equals(ours.amount(), pg.amount())) {
            return mismatch(ours.orderId(), ours.eventType(), MismatchType.AMOUNT, ours.amount(), pg.amount());
        }
        return null;
    }

    /**
     * PG 줄(D 안) → 우리. 금액 비교는 우리 쪽 판정(oursToPg)이 맡으므로 여기서는 존재 여부만 본다.
     * 불일치가 아니면 null.
     */
    public Mismatch pgToOurs(PgLine pg, Optional<PaymentEventTimes> payment) {
        if (payment.isEmpty()) {
            return mismatch(pg.orderId(), pg.eventType(), MismatchType.PG_ONLY, null, pg.amount());
        }
        PaymentEventTimes ours = payment.get();
        LocalDateTime ourAt = MockPgLedger.APPROVE.equals(pg.eventType()) ? ours.approvedAt() : ours.cancelledAt();
        if (ourAt != null && (inDay(ourAt) || withinTolerance(pg.occurredAt(), ourAt))) {
            return null;
        }
        return mismatch(pg.orderId(), pg.eventType(), MismatchType.STATUS, ours.amount(), pg.amount());
    }

    private boolean inDay(LocalDateTime at) {
        return !at.isBefore(reconDate.atStartOfDay()) && at.isBefore(reconDate.plusDays(1).atStartOfDay());
    }

    private boolean withinTolerance(LocalDateTime pgAt, LocalDateTime ourAt) {
        return !ourAt.isBefore(pgAt) && !Duration.between(pgAt, ourAt).minus(tolerance).isPositive();
    }

    private Mismatch mismatch(String orderId, String eventType, MismatchType type, Long ourAmount, Long pgAmount) {
        return new Mismatch(reconDate, orderId, eventType, type, ourAmount, pgAmount);
    }
}
