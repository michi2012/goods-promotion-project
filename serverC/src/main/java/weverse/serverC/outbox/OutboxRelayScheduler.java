package weverse.serverC.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelayScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelay = 500)
    public void relay() {
        List<OutboxEvent> eventsToPublish = transactionTemplate.execute(status -> {
            List<OutboxEvent> events = outboxEventRepository.findPendingWithLock(500);

            if (events == null || events.isEmpty()) return null;

            List<Long> ids = events.stream().map(OutboxEvent::getId).toList();
            // 상태를 PUBLISHING으로 변경
            outboxEventRepository.updateStatusByIds(ids, OutboxStatus.PUBLISHING);

            return events;
        });

        if (eventsToPublish == null || eventsToPublish.isEmpty()) return;

        for (OutboxEvent event : eventsToPublish) {
            sendToKafkaAsync(event);
        }
    }

    private void sendToKafkaAsync(OutboxEvent event) {
        kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                     .whenComplete((result, ex) -> {
                         if (ex == null) {
                             transactionTemplate.executeWithoutResult(status -> {
                                 outboxEventRepository.updateStatusAndSentAt(event.getId(), OutboxStatus.SENT, LocalDateTime.now());
                             });
                             log.debug("[Outbox] 발행 성공: id={}", event.getId());
                         } else {
                             transactionTemplate.executeWithoutResult(status -> {
                                 outboxEventRepository.updateStatus(event.getId(), OutboxStatus.PENDING);
                             });
                             log.error("[Outbox] 발행 실패 (원복 완료): id={}, topic={}", event.getId(), event.getTopic(), ex);
                         }
                     });
    }

    @Scheduled(fixedDelay = 60_000)
    public void rescueStuckEvents() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(1);
            // PUBLISHING 상태로 멈춘 데이터를 찾아 PENDING으로 롤백
            int rescuedCount = outboxEventRepository.rescueStuckEvents(OutboxStatus.PUBLISHING, OutboxStatus.PENDING, cutoff);

            if (rescuedCount > 0) {
                log.warn("🚨 [Outbox-Rescue] {}개의 PUBLISHING 이벤트를 PENDING으로 원복했습니다.", rescuedCount);
            }
        });
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanup() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
            outboxEventRepository.deleteOldSentEvents(OutboxStatus.SENT, cutoff);
            log.debug("[Outbox] cleanup 완료: cutoff={}", cutoff);
        });
    }
}