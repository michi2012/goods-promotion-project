package promotion.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import promotion.serverC.batch.PgLine;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 대사일별로 적재한 PG 거래 파일 사본 (pg_settlement_lines).
 */
@Repository
@RequiredArgsConstructor
public class PgSettlementLineRepository {

    private static final RowMapper<PgLine> ROW_MAPPER = (rs, rowNum) -> new PgLine(
            rs.getString("order_id"),
            rs.getString("event_type"),
            rs.getObject("amount", Long.class),
            rs.getObject("occurred_at", LocalDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    // 같은 줄을 다시 써도 한 벌만 남는다 (chunk 재시도·재시작). INSERT IGNORE는 길이 초과·형 변환 오류까지
    // 경고로 삼켜 대사 대상이 조용히 빠지므로, 유니크 충돌만 무시하고 다른 오류는 Step 실패로 드러낸다.
    public void insertIgnore(LocalDate reconDate, List<? extends PgLine> lines) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO pg_settlement_lines (recon_date, order_id, event_type, amount, occurred_at) " +
                "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id",
                lines, lines.size(),
                (ps, line) -> {
                    ps.setObject(1, reconDate);
                    ps.setString(2, line.orderId());
                    ps.setString(3, line.eventType());
                    ps.setObject(4, line.amount());
                    ps.setObject(5, line.occurredAt());
                });
    }

    public Optional<PgLine> find(LocalDate reconDate, String orderId, String eventType) {
        return jdbcTemplate.query(
                "SELECT order_id, event_type, amount, occurred_at FROM pg_settlement_lines " +
                "WHERE recon_date = ? AND order_id = ? AND event_type = ?",
                ROW_MAPPER, reconDate, orderId, eventType).stream().findFirst();
    }

    public int deleteByReconDate(LocalDate reconDate) {
        return jdbcTemplate.update("DELETE FROM pg_settlement_lines WHERE recon_date = ?", reconDate);
    }
}
