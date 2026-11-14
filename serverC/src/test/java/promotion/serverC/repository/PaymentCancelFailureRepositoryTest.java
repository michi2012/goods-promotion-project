package promotion.serverC.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import promotion.serverC.config.JpaAuditingConfig;
import promotion.serverC.entity.CancelFailureStatus;
import promotion.serverC.entity.PaymentCancelFailure;
import promotion.serverC.support.AbstractRepositoryTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class PaymentCancelFailureRepositoryTest extends AbstractRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);

    @Autowired
    private PaymentCancelFailureRepository repository;

    @Test
    @DisplayName("Flyway로 만든 테이블에 저장하고 orderId로 조회할 수 있다")
    void saveAndFindByOrderId() {
        repository.save(PaymentCancelFailure.retrying("order-1", "PG 오류", NOW));

        PaymentCancelFailure found = repository.findByOrderId("order-1").orElseThrow();

        assertThat(found.getStatus()).isEqualTo(CancelFailureStatus.RETRYING);
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getNextRetryAt()).isEqualTo(NOW);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 orderId로 두 행을 만들 수 없다")
    void orderIdUnique() {
        repository.saveAndFlush(PaymentCancelFailure.retrying("order-1", "PG 오류", NOW));

        assertThatThrownBy(() -> repository.saveAndFlush(PaymentCancelFailure.retrying("order-1", "PG 오류", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("재시도 대상은 재시도 시각이 지난 RETRYING 행뿐이고, 오래 기다린 순으로 나온다")
    void findDueForRetry() {
        repository.save(PaymentCancelFailure.retrying("due-later", "e", NOW.minusMinutes(1)));
        repository.save(PaymentCancelFailure.retrying("due-first", "e", NOW.minusMinutes(5)));
        repository.save(PaymentCancelFailure.retrying("not-yet", "e", NOW.plusMinutes(1)));
        repository.save(PaymentCancelFailure.exhausted("exhausted", "e"));

        List<PaymentCancelFailure> due = repository.findDueForRetry(NOW, PageRequest.of(0, 10));

        assertThat(due).extracting(PaymentCancelFailure::getOrderId)
                .containsExactly("due-first", "due-later");
    }

    @Test
    @DisplayName("선점은 한 번만 성공하고, 선점된 행은 재시도 대상에서 빠진다")
    void claimForRetryOnlyOnce() {
        PaymentCancelFailure failure = repository.saveAndFlush(
                PaymentCancelFailure.retrying("order-1", "e", NOW.minusMinutes(1)));
        LocalDateTime leaseUntil = NOW.plusMinutes(5);

        int first = repository.claimForRetry(failure.getId(), NOW, leaseUntil);
        int second = repository.claimForRetry(failure.getId(), NOW, leaseUntil);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(repository.findDueForRetry(NOW, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    @DisplayName("상태별 목록은 최근 행부터 나온다")
    void findByStatus() {
        repository.save(PaymentCancelFailure.exhausted("old", "e"));
        repository.save(PaymentCancelFailure.exhausted("new", "e"));
        repository.save(PaymentCancelFailure.retrying("retrying", "e", NOW));

        List<PaymentCancelFailure> exhausted = repository.findByStatusOrderByIdDesc(CancelFailureStatus.EXHAUSTED);

        assertThat(exhausted).extracting(PaymentCancelFailure::getOrderId).containsExactly("new", "old");
    }
}
