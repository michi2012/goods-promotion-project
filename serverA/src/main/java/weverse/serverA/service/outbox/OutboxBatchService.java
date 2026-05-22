package weverse.serverA.service.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.PurchaseMessage;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxBatchService {

    private final JdbcTemplate jdbcTemplate;

    // 💡 별도의 클래스로 분리하고 @Transactional을 걸었기 때문에, 500개 중 1개라도 실패하면 DB는 완벽히 0건으로 롤백됩니다.
    @Transactional
    public void batchInsert(List<PurchaseMessage> messages) {
        String sql = "INSERT INTO request_outbox " +
                "(trace_id, user_id, goods_id, quantity, payment_method, " +
                "shipping_address, zip_code, phone_number, email, delivery_memo, " +
                "client_ip, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', NOW())";

        jdbcTemplate.batchUpdate(sql, messages, messages.size(), (ps, msg) -> {
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
        });
    }
}