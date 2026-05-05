package weverse.serverA.service.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import weverse.serverA.repository.OutboxRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxManagerService {

    private final OutboxRepository outboxRepository;

    @Scheduled(cron = "0 0 4 * * *")
    public void cleanupPublishedOutbox() {
        log.info("[OutboxManager] 완료된 아웃박스 데이터 정리를 시작합니다.");

        // 하루 전 데이터만 삭제 (만약의 사태를 대비해 최근 하루 치는 로그성으로 남겨둠)
        LocalDateTime oneDayAgo = LocalDateTime.now().minusDays(1);
        int deleteLimit = 5000; // 한 번에 지울 개수
        int totalDeleted = 0;

        while (true) {
            int deletedCount = outboxRepository.deleteOldOutboxData(oneDayAgo, deleteLimit);
            totalDeleted += deletedCount;

            if (deletedCount < deleteLimit) {
                break; // 지울 데이터가 더 없으면 루프 종료
            }

            // DB CPU가 쉴 틈을 주기 위해 잠깐 대기
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {}
        }

        log.info("[OutboxManager] 완료된 아웃박스 데이터 정리 완료. 총 {}건 삭제", totalDeleted);
    }

    @Scheduled(fixedDelay = 5000)
    public void recoverZombieMessages() {
        // 선착순에서 15초 동안 처리가 안 됐다는 건 해당 워커 스레드가 죽었거나 DB 락에 걸렸다는 뜻
        LocalDateTime thresholdTime = LocalDateTime.now().minusSeconds(15);

        try {
            int recoveredCount = outboxRepository.recoverZombieMessages(thresholdTime);

            if (recoveredCount > 0) {
                log.warn("🚨 [고속 장애 복구] 15초 이상 응답 없는 좀비 메시지 {}건을 PENDING으로 강제 원복하여 즉시 재처리합니다.", recoveredCount);
            }
        } catch (Exception e) {
            log.error("[OutboxManager] 좀비 메시지 복구 중 에러 발생", e);
        }
    }
}