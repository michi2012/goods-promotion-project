package promotion.serverC.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 모의 PG의 거래 원장 (실제 PG사 내부 데이터 흉내). 일자별 PG 거래 파일을 만드는 데 쓴다.
 * 외부 PG의 거래는 우리 트랜잭션이 롤백돼도 남아야 하므로 기록은 항상 별도 트랜잭션이다.
 */
@Component
@RequiredArgsConstructor
public class MockPgLedger {

    public static final String APPROVE = "APPROVE";
    public static final String CANCEL = "CANCEL";

    private final JdbcTemplate jdbcTemplate;

    // 같은 주문의 같은 이벤트는 한 번만 남는다 (멱등 키 재사용·재시도).
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String orderId, String eventType, Long amount, LocalDateTime occurredAt) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO mock_pg_transactions (order_id, event_type, amount, occurred_at) VALUES (?, ?, ?, ?)",
                orderId, eventType, amount, occurredAt);
    }

    // [from, to) 거래를 발생 순서대로 한 건씩 넘긴다. MySQL 드라이버 기본값이라 결과는 메모리에 한 번에 올라온다
    // (스트리밍 아님) — 모의 PG 원장은 작고, 대량 시드(BL-047)는 원장을 거치지 않고 파일을 직접 만든다.
    @Transactional(readOnly = true)
    public void forEachBetween(LocalDateTime from, LocalDateTime to, Consumer<Entry> consumer) {
        jdbcTemplate.query(
                "SELECT order_id, event_type, amount, occurred_at FROM mock_pg_transactions " +
                "WHERE occurred_at >= ? AND occurred_at < ? ORDER BY occurred_at, id",
                rs -> {
                    consumer.accept(new Entry(
                            rs.getString("order_id"),
                            rs.getString("event_type"),
                            rs.getObject("amount", Long.class),
                            rs.getObject("occurred_at", LocalDateTime.class)));
                },
                from, to);
    }

    public record Entry(String orderId, String eventType, Long amount, LocalDateTime occurredAt) {
    }
}
