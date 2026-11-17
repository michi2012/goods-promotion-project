package promotion.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import promotion.serverC.dto.PaymentResponse;
import promotion.serverC.dto.PurchaseMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentRepository {
    private final JdbcTemplate jdbcTemplate;

    public boolean claimOrder(PurchaseMessage msg) {
        String sql = "INSERT INTO payments (order_id, user_id, goods_id, quantity, amount, currency, payment_method, " +
                "shipping_address, zip_code, phone_number, email, delivery_memo, client_ip, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', NOW())";
        try {
            jdbcTemplate.update(sql,
                    msg.orderId(), msg.userId(), msg.goodsId(), msg.quantity(),
                    msg.totalAmount(), msg.currency(),
                    msg.paymentMethod(), msg.shippingAddress(), msg.zipCode(),
                    msg.phoneNumber(), msg.email(), msg.deliveryMemo(), msg.clientIp());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void updateOrderStatus(List<String> orderIds, String status) {
        if (orderIds.isEmpty()) return;

        String inSql = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        String sql = "UPDATE payments SET status = ? WHERE order_id IN (" + inSql + ")";

        List<Object> params = new ArrayList<>();
        params.add(status);
        params.addAll(orderIds);

        jdbcTemplate.update(sql, params.toArray());
    }

    // PG 승인 확정. approvedAt은 대사 기준 시각(앱 시계 — 모의 PG 원장과 같은 시계).
    public void markPaid(String orderId, LocalDateTime approvedAt) {
        jdbcTemplate.update(
                "UPDATE payments SET status = 'PAID', approved_at = ? WHERE order_id = ?",
                approvedAt, orderId);
    }

    // PAID일 때만 CANCELLED로 바꾸고 취소 시각을 남긴다. 바뀐 행 수(0 또는 1)를 돌려준다.
    public int markCancelled(String orderId, LocalDateTime cancelledAt) {
        return jdbcTemplate.update(
                "UPDATE payments SET status = 'CANCELLED', cancelled_at = ? WHERE order_id = ? AND status = 'PAID'",
                cancelledAt, orderId);
    }

    // PG는 승인했지만 금액 불일치로 즉시 망취소한 결제. PG에는 승인·취소가 모두 남으므로 두 시각을 함께 기록한다.
    public void markFailedAfterNetCancel(String orderId, LocalDateTime approvedAt, LocalDateTime cancelledAt) {
        jdbcTemplate.update(
                "UPDATE payments SET status = 'FAILED', approved_at = ?, cancelled_at = ? WHERE order_id = ?",
                approvedAt, cancelledAt, orderId);
    }

    public Optional<PaymentResponse> findByOrderId(String orderId) {
        String sql = "SELECT order_id, user_id, goods_id, quantity, amount, currency, payment_method, status, created_at " +
                     "FROM payments WHERE order_id = ?";
        List<PaymentResponse> results = jdbcTemplate.query(sql, PAYMENT_ROW_MAPPER, orderId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<PaymentResponse> findByUserId(Long userId, int page, int size) {
        String sql = "SELECT order_id, user_id, goods_id, quantity, amount, currency, payment_method, status, created_at " +
                     "FROM payments WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, PAYMENT_ROW_MAPPER, userId, size, (long) page * size);
    }

    private static final RowMapper<PaymentResponse> PAYMENT_ROW_MAPPER =
            (rs, rowNum) -> new PaymentResponse(
                    rs.getString("order_id"),
                    rs.getLong("user_id"),
                    rs.getLong("goods_id"),
                    rs.getInt("quantity"),
                    rs.getObject("amount", Long.class),
                    rs.getString("currency"),
                    rs.getString("payment_method"),
                    rs.getString("status"),
                    rs.getObject("created_at", LocalDateTime.class)
            );
}
