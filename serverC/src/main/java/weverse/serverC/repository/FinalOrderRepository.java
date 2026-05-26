package weverse.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import weverse.serverC.dto.PurchaseMessage;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FinalOrderRepository {
    private final JdbcTemplate jdbcTemplate;

    public List<PurchaseMessage> claimOrders(List<PurchaseMessage> messages) {
        if (messages.isEmpty()) {
            return new ArrayList<>();
        }

        String insertSql = "INSERT INTO final_order (order_id, user_id, goods_id, quantity, payment_method, " +
                "shipping_address, zip_code, phone_number, email, delivery_memo, client_ip, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', NOW()) " +
                "ON DUPLICATE KEY UPDATE order_id = order_id";

        int[] results = jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PurchaseMessage msg = messages.get(i);
                ps.setString(1, msg.orderId());
                ps.setLong(2, msg.userId());
                ps.setLong(3, msg.goodsId());
                ps.setInt(4, msg.quantity());
                ps.setString(5, msg.paymentMethod());
                ps.setString(6, msg.shippingAddress());
                ps.setString(7, msg.zipCode());
                ps.setString(8, msg.phoneNumber());
                ps.setString(9, msg.email());
                ps.setString(10, msg.deliveryMemo());
                ps.setString(11, msg.clientIp());
            }
            @Override
            public int getBatchSize() { return messages.size(); }
        });

        List<PurchaseMessage> claimed = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            // result == 1: 신규 삽입 (선점 성공)
            // result == 0: 중복 no-op (다른 워커가 이미 처리)
            // result == SUCCESS_NO_INFO(-2): rewriteBatchedStatements=true로 배치 재작성된 경우
            if (results[i] > 0 || results[i] == Statement.SUCCESS_NO_INFO) {
                claimed.add(messages.get(i));
            }
        }
        return claimed;
    }

    public void updateOrderStatus(List<String> orderIds, String status) {
        if (orderIds.isEmpty()) return;

        String inSql = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
        String sql = "UPDATE final_order SET status = ? WHERE order_id IN (" + inSql + ")";

        List<Object> params = new ArrayList<>();
        params.add(status);
        params.addAll(orderIds);

        jdbcTemplate.update(sql, params.toArray());
    }

    public boolean existsByOrderId(String orderId) {
        String sql = "SELECT COUNT(1) FROM final_order WHERE order_id = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, orderId);
        return count != null && count > 0;
    }
}