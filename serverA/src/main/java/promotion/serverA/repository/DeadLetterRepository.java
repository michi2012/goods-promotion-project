package promotion.serverA.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import promotion.serverA.entity.DeadLetter;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
}
