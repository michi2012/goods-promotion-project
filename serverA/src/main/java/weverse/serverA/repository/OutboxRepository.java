package weverse.serverA.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;

import java.util.List;

public interface OutboxRepository extends JpaRepository<RequestOutbox, Long> {

    List<RequestOutbox> findByStatus(OutboxStatus status, Pageable pageable);

    boolean existsByUserIdAndStatusIn(Long userId, List<OutboxStatus> statuses);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE RequestOutbox r SET r.status = :status WHERE r.id IN :ids")
    int updateStatusByIds(@Param("status") OutboxStatus status, @Param("ids") List<Long> ids);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE RequestOutbox r SET r.status = 'COMPENSATED' WHERE r.traceId = :traceId AND r.status != 'COMPENSATED'")
    int markAsCompensatedAtomically(@Param("traceId") String traceId);

}