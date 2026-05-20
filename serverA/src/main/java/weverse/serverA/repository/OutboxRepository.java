package weverse.serverA.repository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<RequestOutbox, Long> {

    List<RequestOutbox> findByStatus(OutboxStatus status, Pageable pageable);

    boolean existsByUserIdAndStatusIn(Long userId, List<OutboxStatus> statuses);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE RequestOutbox r SET r.status = :status WHERE r.id IN :ids")
    int updateStatusByIds(@Param("status") OutboxStatus status, @Param("ids") List<Long> ids);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RequestOutbox r SET r.status = 'COMPENSATED' WHERE r.traceId = :traceId AND r.status IN ('PUBLISHING', 'SENT')")
    int markAsCompensatedAtomically(@Param("traceId") String traceId);

    // PESSIMISTIC_WRITE로 락을 걸고, "-2"(SKIP LOCKED) 힌트를 주어
    // 다른 트랜잭션이 선점한 Row는 대기하지 않고 즉시 무시(Skip)한 뒤 다음 Row를 읽습니다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT r FROM RequestOutbox r WHERE r.status = :status")
    List<RequestOutbox> findClaimableRecords(OutboxStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RequestOutbox r SET r.status = :status WHERE r.traceId = :traceId")
    void updateStatus(@Param("traceId") String traceId, @Param("status") String status);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE RequestOutbox r SET r.status = 'FAIL' WHERE r.status = 'PENDING' AND r.goodsId IN :goodsIds")
    int bulkFailPendingByGoodsIds(@Param("goodsIds") List<Long> goodsIds);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM request_outbox WHERE status IN ('SUCCESS', 'FAIL') AND created_at < :targetTime LIMIT :limitCount", nativeQuery = true)
    int deleteOldOutboxData(@Param("targetTime") LocalDateTime targetTime, @Param("limitCount") int limitCount);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE RequestOutbox r SET r.status = 'PENDING' " +
            "WHERE r.status = 'PUBLISHING' AND r.updatedAt < :thresholdTime")
    int recoverZombieMessages(@Param("thresholdTime") LocalDateTime thresholdTime);

}