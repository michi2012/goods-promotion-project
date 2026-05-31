package weverse.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import weverse.serverC.dto.PaymentResponse;
import weverse.serverC.dto.PurchaseMessage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentRepository {
    private final JdbcTemplate jdbcTemplate;

    public boolean claimOrder(PurchaseMessage msg) {
        String sql = "INSERT INTO payments (order_id, user_id, goods_id, quantity, payment_method, " +
                "shipping_address, zip_code, phone_number, email, delivery_memo, client_ip, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', NOW())";
        try {
            jdbcTemplate.update(sql,
                    msg.orderId(), msg.userId(), msg.goodsId(), msg.quantity(),
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

    public Optional<PaymentResponse> findByOrderId(String orderId) {
        String sql = "SELECT order_id, user_id, goods_id, quantity, payment_method, status, created_at " +
                     "FROM payments WHERE order_id = ?";
        List<PaymentResponse> results = jdbcTemplate.query(sql, PAYMENT_ROW_MAPPER, orderId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<PaymentResponse> findByUserId(Long userId, int page, int size) {
        String sql = "SELECT order_id, user_id, goods_id, quantity, payment_method, status, created_at " +
                     "FROM payments WHERE user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, PAYMENT_ROW_MAPPER, userId, size, (long) page * size);
    }

    private static final RowMapper<PaymentResponse> PAYMENT_ROW_MAPPER =
            (rs, rowNum) -> new PaymentResponse(
                    rs.getString("order_id"),
                    rs.getLong("user_id"),
                    rs.getLong("goods_id"),
                    rs.getInt("quantity"),
                    rs.getString("payment_method"),
                    rs.getString("status"),
                    rs.getObject("created_at", LocalDateTime.class)
            );
}
