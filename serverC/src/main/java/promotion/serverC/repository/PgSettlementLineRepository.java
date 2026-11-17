package promotion.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import promotion.serverC.batch.PgLine;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // 같은 대사일로 적재된 줄(D 파일 + D-1 꼬리) 중 한 이벤트 종류의 줄을 chunk 단위로 한 번에 조회한다.
    // 결과에 없는 orderId는 그 이벤트의 PG 줄이 없는 것. (recon_date, order_id, event_type) 유일 키라 주문당 최대 1줄.
    public Map<String, PgLine> findAll(LocalDate reconDate, String eventType, Collection<String> orderIds) {
        Map<String, PgLine> found = new HashMap<>();
        if (orderIds.isEmpty()) {
            return found;
        }
        List<Object> args = new ArrayList<>(orderIds.size() + 2);
        args.add(reconDate);
        args.add(eventType);
        args.addAll(orderIds);
        jdbcTemplate.query(
                "SELECT order_id, event_type, amount, occurred_at FROM pg_settlement_lines " +
                "WHERE recon_date = ? AND event_type = ? AND order_id IN ("
                        + String.join(",", Collections.nCopies(orderIds.size(), "?")) + ")",
                ROW_MAPPER, args.toArray()).forEach(line -> found.put(line.orderId(), line));
        return found;
    }

    public int deleteByReconDate(LocalDate reconDate) {
        return jdbcTemplate.update("DELETE FROM pg_settlement_lines WHERE recon_date = ?", reconDate);
    }
}
