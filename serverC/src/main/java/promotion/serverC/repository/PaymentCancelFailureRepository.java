package promotion.serverC.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import promotion.serverC.entity.CancelFailureStatus;
import promotion.serverC.entity.PaymentCancelFailure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentCancelFailureRepository extends JpaRepository<PaymentCancelFailure, Long> {

    Optional<PaymentCancelFailure> findByOrderId(String orderId);

    List<PaymentCancelFailure> findByStatusOrderByIdDesc(CancelFailureStatus status);

    // 자동 재시도 대상: 재시도 시각이 지난 RETRYING 행 (오래 기다린 순)
    @Query("""
            select f from PaymentCancelFailure f
            where f.status = promotion.serverC.entity.CancelFailureStatus.RETRYING
              and f.nextRetryAt <= :now
            order by f.nextRetryAt asc
            """)
    List<PaymentCancelFailure> findDueForRetry(@Param("now") LocalDateTime now, Pageable pageable);

    // 선점: 아직 재시도 시각이 지난 RETRYING이면 next_retry_at을 leaseUntil로 미뤄 다른 인스턴스가 집지 못하게 한다.
    // 1이면 선점 성공, 0이면 다른 인스턴스가 먼저 집었거나 상태가 바뀜.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentCancelFailure f
            set f.nextRetryAt = :leaseUntil
            where f.id = :id
              and f.status = promotion.serverC.entity.CancelFailureStatus.RETRYING
              and f.nextRetryAt <= :now
            """)
    int claimForRetry(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);
}
