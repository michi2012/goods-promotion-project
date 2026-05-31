package promotion.serverA.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent o WHERE o.createdAt < :cutoff")
    void deleteOldEvents(@Param("cutoff") LocalDateTime cutoff);
}