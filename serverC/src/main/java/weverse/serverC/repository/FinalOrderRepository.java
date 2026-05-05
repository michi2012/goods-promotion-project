package weverse.serverC.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import weverse.serverC.dto.PurchaseMessage;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class FinalOrderRepository {
    private final JdbcTemplate jdbcTemplate;

    public List<PurchaseMessage> claimOrders(List<PurchaseMessage> messages) {
        String sql = "INSERT IGNORE INTO final_order (trace_id, user_id, goods_id, quantity, payment_method, " +
                "shipping_address, zip_code, phone_number, email, delivery_memo, client_ip, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', NOW())";

        int[] results = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                PurchaseMessage msg = messages.get(i);
                ps.setString(1, msg.traceId());
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

        // 결과 배열에서 1(성공)인 경우만 '내가 선점한 결제 건'으로 간주하여 반환
        List<PurchaseMessage> claimed = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            // Statement.SUCCESS_NO_INFO(-2) 이거나 0보다 크면 성공으로 간주
            if (results[i] > 0 || results[i] == -2) {
                claimed.add(messages.get(i));
            }
        }
        return claimed;
    }

    public void updateOrderStatus(List<String> traceIds, String status) {
        if (traceIds.isEmpty()) return;

        String inSql = String.join(",", java.util.Collections.nCopies(traceIds.size(), "?"));
        String sql = "UPDATE final_order SET status = ? WHERE trace_id IN (" + inSql + ")";

        List<Object> params = new ArrayList<>();
        params.add(status);
        params.addAll(traceIds);

        jdbcTemplate.update(sql, params.toArray());
    }
}