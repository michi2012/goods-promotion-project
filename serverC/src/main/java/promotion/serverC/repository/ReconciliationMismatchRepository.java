package promotion.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import promotion.serverC.batch.Mismatch;
import promotion.serverC.batch.MismatchType;
import promotion.serverC.service.ReconciliationTime;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 대사 결과(불일치). 날짜당 한 벌 — 재실행은 그 날짜를 지우고 다시 쓴다 (명세 P-7).
 */
@Repository
@RequiredArgsConstructor
public class ReconciliationMismatchRepository {

    private final JdbcTemplate jdbcTemplate;

    // 같은 (날짜, 주문, 이벤트)를 다시 써도 한 벌만 남는다 (chunk 재시도·재시작).
    public void insertIgnore(List<? extends Mismatch> mismatches) {
        LocalDateTime now = ReconciliationTime.now();
        jdbcTemplate.batchUpdate(
                "INSERT IGNORE INTO reconciliation_mismatches " +
                "(recon_date, order_id, event_type, mismatch_type, our_amount, pg_amount, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                mismatches, mismatches.size(),
                (ps, m) -> {
                    ps.setObject(1, m.reconDate());
                    ps.setString(2, m.orderId());
                    ps.setString(3, m.eventType());
                    ps.setString(4, m.type().name());
                    ps.setObject(5, m.ourAmount());
                    ps.setObject(6, m.pgAmount());
                    ps.setObject(7, now);
                });
    }

    public int deleteByReconDate(LocalDate reconDate) {
        return jdbcTemplate.update("DELETE FROM reconciliation_mismatches WHERE recon_date = ?", reconDate);
    }

    public Map<MismatchType, Long> countByType(LocalDate reconDate) {
        Map<MismatchType, Long> counts = new EnumMap<>(MismatchType.class);
        jdbcTemplate.query(
                "SELECT mismatch_type, COUNT(*) AS cnt FROM reconciliation_mismatches " +
                "WHERE recon_date = ? GROUP BY mismatch_type",
                rs -> {
                    counts.put(MismatchType.valueOf(rs.getString("mismatch_type")), rs.getLong("cnt"));
                },
                reconDate);
        return counts;
    }
}
