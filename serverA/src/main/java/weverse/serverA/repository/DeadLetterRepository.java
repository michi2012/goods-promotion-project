package weverse.serverA.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import weverse.serverA.entity.DeadLetter;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
}
