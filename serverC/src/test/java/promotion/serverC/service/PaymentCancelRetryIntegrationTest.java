package promotion.serverC.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import promotion.serverC.config.JpaAuditingConfig;
import promotion.serverC.entity.CancelFailureStatus;
import promotion.serverC.entity.PaymentCancelFailure;
import promotion.serverC.exception.PgPaymentException;
import promotion.serverC.outbox.OutboxEventRepository;
import promotion.serverC.outbox.OutboxEventService;
import promotion.serverC.repository.PaymentCancelFailureRepository;
import promotion.serverC.repository.PaymentRepository;
import promotion.serverC.support.AbstractRepositoryTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 실제 MySQL(Flyway 스키마)에서 PG 취소 실패 → 자동 재시도 → 소진/해결 흐름을 검증한다.
 * PG만 대체하고, 트랜잭션·payments 상태 변경·Outbox 저장·실패 기록은 실제로 일어난다.
 * 각 단계가 자기 트랜잭션을 커밋해야 하므로 테스트 트랜잭션은 쓰지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({JpaAuditingConfig.class, PaymentCancelService.class, PaymentRepository.class, OutboxEventService.class,
        PaymentCancelRetryIntegrationTest.MetricsConfig.class})
class PaymentCancelRetryIntegrationTest extends AbstractRepositoryTest {

    private static final String ORDER_ID = "it-order-1";

    @TestConfiguration
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @MockitoBean
    private PgClient pgClient;

    @Autowired
    private PaymentCancelService service;
    @Autowired
    private PaymentCancelFailureRepository failureRepository;
    @Autowired
    private OutboxEventRepository outboxEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        failureRepository.deleteAll();
        outboxEventRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM payments");
        jdbcTemplate.update("INSERT INTO payments (order_id, user_id, goods_id, quantity, payment_method, "
                + "shipping_address, zip_code, phone_number, email, status, created_at) "
                + "VALUES (?, 1, 1, 1, 'CARD', '주소', '01234', '010', 'a@b.c', 'PAID', NOW())", ORDER_ID);
    }

    @Test
    @DisplayName("DLT 기록 후 자동 재시도가 6번 모두 실패하면 EXHAUSTED가 되고 결제는 PAID로 남는다")
    void retryUntilExhausted() {
        when(pgClient.cancelPayment(ORDER_ID)).thenThrow(new PgPaymentException("PG 장애"));
        service.recordDltArrival(ORDER_ID, "Kafka 재시도 소진");
        double before = meterRegistry.counter("business_payment_cancel_retry_exhausted_total").count();

        LocalDateTime now = LocalDateTime.now();
        for (int i = 1; i <= 6; i++) {
            now = now.plusMinutes(11); // 매번 재시도 시각(10분 뒤)을 넘긴다
            assertThat(service.retryDue(now)).isEqualTo(1);
        }

        PaymentCancelFailure failure = failureRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(failure.getStatus()).isEqualTo(CancelFailureStatus.EXHAUSTED);
        assertThat(failure.getAttempts()).isEqualTo(6);
        assertThat(failure.getNextRetryAt()).isNull();
        assertThat(service.retryDue(now.plusMinutes(11))).isZero(); // 더는 자동 재시도하지 않는다
        assertThat(paymentStatus()).isEqualTo("PAID");
        assertThat(outboxEventRepository.count()).isZero();
        assertThat(meterRegistry.counter("business_payment_cancel_retry_exhausted_total").count())
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("자동 재시도가 성공하면 RESOLVED가 되고 결제는 CANCELLED, payment-cancelled가 Outbox에 저장된다")
    void retrySucceeds() {
        when(pgClient.cancelPayment(ORDER_ID)).thenReturn(PgCancelResult.CANCELLED);
        service.recordDltArrival(ORDER_ID, "Kafka 재시도 소진");

        int processed = service.retryDue(LocalDateTime.now().plusMinutes(11));

        assertThat(processed).isEqualTo(1);
        assertThat(failureRepository.findByOrderId(ORDER_ID).orElseThrow().getStatus())
                .isEqualTo(CancelFailureStatus.RESOLVED);
        assertThat(paymentStatus()).isEqualTo("CANCELLED");
        assertThat(outboxEventRepository.findAll())
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getTopic()).isEqualTo("payment-cancelled");
                    assertThat(e.getPayload()).contains(ORDER_ID);
                });
    }

    @Test
    @DisplayName("재시도 시각 전에는 재시도하지 않는다")
    void notDueYet() {
        service.recordDltArrival(ORDER_ID, "Kafka 재시도 소진");

        assertThat(service.retryDue(LocalDateTime.now().plusMinutes(5))).isZero();
    }

    private String paymentStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, ORDER_ID);
    }
}
