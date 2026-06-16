package promotion.serverA.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import promotion.serverA.entity.DeadLetter;
import promotion.serverA.entity.DltStatus;

import java.util.List;
import java.util.Optional;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
    Optional<DeadLetter> findByOrderId(String orderId);
    List<DeadLetter> findAllByStatus(DltStatus status);
    long countByStatus(DltStatus status);
}
