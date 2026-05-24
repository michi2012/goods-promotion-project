package weverse.serverA.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
            outboxEventRepository.deleteOldEvents(cutoff);
            log.debug("[Outbox] cleanup 완료: cutoff={}", cutoff);
        });
    }
}
