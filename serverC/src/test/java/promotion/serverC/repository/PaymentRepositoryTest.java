package promotion.serverC.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import promotion.serverC.dto.PurchaseMessage;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PaymentRepository.class)
class PaymentRepositoryTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS payments (" +
                "order_id VARCHAR(255) PRIMARY KEY, " +
                "user_id BIGINT NOT NULL, " +
                "goods_id BIGINT NOT NULL, " +
                "quantity INT NOT NULL, " +
                "payment_method VARCHAR(50) NOT NULL, " +
                "shipping_address VARCHAR(500), " +
                "zip_code VARCHAR(20), " +
                "phone_number VARCHAR(20), " +
                "email VARCHAR(255), " +
                "delivery_memo VARCHAR(500), " +
                "client_ip VARCHAR(50), " +
                "status VARCHAR(20) NOT NULL, " +
                "created_at DATETIME NOT NULL)"
        );
    }

    @Test
    @DisplayName("신규 주문은 INSERT 성공 후 true를 반환한다")
    void claimOrder_신규주문_선점성공() {
        assertThat(paymentRepository.claimOrder(message("order-new"))).isTrue();
    }

    @Test
    @DisplayName("이미 PAID 처리된 중복 주문은 ON DUPLICATE KEY no-op 후 false를 반환한다")
    void claimOrder_중복주문_제외() {
        // given: Kafka 재전달 시나리오 — 이미 PAID 상태로 존재하는 레코드
        jdbcTemplate.update(
                "INSERT INTO payments (order_id, user_id, goods_id, quantity, payment_method, " +
                "shipping_address, zip_code, phone_number, email, delivery_memo, client_ip, status, created_at) " +
                "VALUES (?, 1, 4, 1, 'CARD', '주소', '01234', '010-0000-0000', 'x@x.com', '', '127.0.0.1', 'PAID', NOW())",
                "order-dup"
        );

        assertThat(paymentRepository.claimOrder(message("order-dup"))).isFalse();
    }

    private PurchaseMessage message(String orderId) {
        return new PurchaseMessage(orderId, 1L, 4L, 1, "CARD", "주소", "01234", "010-1234-5678", "test@test.com", "메모", "127.0.0.1");
    }
}
