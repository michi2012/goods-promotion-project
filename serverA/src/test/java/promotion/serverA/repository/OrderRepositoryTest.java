package promotion.serverA.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OrderRepositoryTest {

    @Container
    static MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    private void saveOrder(String orderId, OrderStatus status, LocalDateTime createdAt) {
        orderRepository.save(Order.builder()
                .orderId(orderId)
                .userId(1L)
                .goodsId(10L)
                .quantity(1)
                .clientIp("127.0.0.1")
                .status(status)
                .build());
        orderRepository.flush();
        // created_at은 Auditing이 채우므로 테스트용 시각은 네이티브 UPDATE로 맞춘다.
        entityManager.createNativeQuery("UPDATE orders SET created_at = :createdAt WHERE order_id = :orderId")
                .setParameter("createdAt", createdAt)
                .setParameter("orderId", orderId)
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    @DisplayName("종료 주문 재대사 조회: 지정한 상태이면서 생성 시각이 범위 안인 주문만 반환한다.")
    void findByStatusInAndCreatedAtBetween() {
        LocalDateTime now = LocalDateTime.now();
        saveOrder("expired-in-range", OrderStatus.EXPIRED, now.minusHours(1));
        saveOrder("failed-in-range", OrderStatus.FAILED, now.minusMinutes(30));
        saveOrder("paid-in-range", OrderStatus.PAID, now.minusHours(1));
        saveOrder("expired-too-old", OrderStatus.EXPIRED, now.minusHours(25));
        saveOrder("expired-too-recent", OrderStatus.EXPIRED, now.minusMinutes(5));

        List<Order> result = orderRepository.findByStatusInAndCreatedAtBetween(
                List.of(OrderStatus.EXPIRED, OrderStatus.FAILED), now.minusHours(24), now.minusMinutes(10));

        assertThat(result).extracting(Order::getOrderId)
                .containsExactlyInAnyOrder("expired-in-range", "failed-in-range");
    }
}
