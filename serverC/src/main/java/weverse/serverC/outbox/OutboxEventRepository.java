package weverse.serverC.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = "SELECT * FROM outbox_event WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findPendingWithLock(@Param("limit") int limit);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.status = :newStatus WHERE o.id IN :ids")
    void updateStatusByIds(@Param("ids") List<Long> ids, @Param("newStatus") OutboxStatus newStatus);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.status = :newStatus, o.sentAt = :sentAt WHERE o.id = :id")
    void updateStatusAndSentAt(@Param("id") Long id, @Param("newStatus") OutboxStatus newStatus, @Param("sentAt") LocalDateTime sentAt);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.status = :newStatus WHERE o.id = :id")
    void updateStatus(@Param("id") Long id, @Param("newStatus") OutboxStatus newStatus);

    // createdAt -> updatedAt으로 변경하여 진짜 멈춘 데이터만 롤백하도록 수정
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OutboxEvent o SET o.status = :targetStatus WHERE o.status = :currentStatus AND o.updatedAt < :cutoff")
    int rescueStuckEvents(@Param("currentStatus") OutboxStatus currentStatus, @Param("targetStatus") OutboxStatus targetStatus, @Param("cutoff") LocalDateTime cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent o WHERE o.status = :status AND o.sentAt < :cutoff")
    void deleteOldSentEvents(@Param("status") OutboxStatus status, @Param("cutoff") LocalDateTime cutoff);
}