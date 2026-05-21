package weverse.serverA.service.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.OutboxRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimer {

    private final OutboxRepository outboxRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recoverZombies(LocalDateTime thresholdTime) {
        List<Long> zombieIds = outboxRepository.findZombieIds(thresholdTime, 500);

        if (zombieIds.isEmpty()) return 0;

        Collections.sort(zombieIds);

        return outboxRepository.updateStatusByIds(OutboxStatus.PENDING, zombieIds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<RequestOutbox> claimSuccessRecords() {
        // 1. SKIP LOCKED로 안전하게 500건 선점 조회
        List<RequestOutbox> list = outboxRepository.findClaimableRecords(OutboxStatus.SUCCESS.name(), 500);

        if (!list.isEmpty()) {
            // 2. 벌크 업데이트에 사용할 ID 목록 추출
            List<Long> ids = list.stream().map(RequestOutbox::getId).toList();

            // 3. 단 1방의 UPDATE ... WHERE id IN (...) 쿼리로 DB 상태 변경
            outboxRepository.updateStatusByIds(OutboxStatus.PUBLISHING, ids);

            // 4. 리포지토리의 clearAutomatically 옵션으로 인해 list 내 엔티티들은 '준영속 상태'가 됨
            // 메모리상에서만 상태를 맞춰서 반환하며, 커밋 시점에 Dirty Checking 개별 쿼리가 절대 나가지 않음
            list.forEach(RequestOutbox::markAsPublishing);
        }

        return list;
    }
}