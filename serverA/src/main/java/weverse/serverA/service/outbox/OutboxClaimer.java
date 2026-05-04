package weverse.serverA.service.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.OutboxRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxClaimer {

    private final OutboxRepository outboxRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<RequestOutbox> claimSuccessRecords() {
        // 락(Lock)과 스킵(Skip)이 적용된 전용 메서드로 조회
        // 서버 A-1이 1~500번을 읽는 순간 락이 걸리므로,
        // 0.001초 뒤에 온 서버 A-2는 1~500번을 무시하고 501~1000번을 읽어갑니다!
        List<RequestOutbox> list = outboxRepository.findClaimableRecords(OutboxStatus.SUCCESS, PageRequest.of(0, 500));

        if (!list.isEmpty()) {
            list.forEach(RequestOutbox::markAsPublishing);
        }

        return list;
    }
}