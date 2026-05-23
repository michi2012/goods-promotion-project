package weverse.serverA.service.dlt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.entity.DeadLetter;
import weverse.serverA.entity.DltStatus;
import weverse.serverA.exception.AlreadyResolvedDltException;
import weverse.serverA.exception.GoodsNotFoundException;
import weverse.serverA.repository.DeadLetterRepository;
import weverse.serverA.repository.GoodsRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    private final DeadLetterRepository deadLetterRepository;
    private final GoodsRepository goodsRepository;

    // 💡 1. 격리 저장 (본 트랜잭션이 롤백되어도 이 기록은 남음)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDeadLetter(String traceId, Long goodsId, int quantity, String reason) {
        deadLetterRepository.save(DeadLetter.builder()
                                            .traceId(traceId)
                                            .goodsId(goodsId)
                                            .quantity(quantity)
                                            .reason(reason)
                                            .build());
    }

    // 💡 2. Admin 전용 재처리 로직 (수동 복구)
    @Transactional
    public void retryDeadLetter(Long dltId) {
        DeadLetter dlt = deadLetterRepository.findById(dltId)
                                             .orElseThrow(() -> new IllegalArgumentException("DLT 기록 없음"));

        if (dlt.getStatus() == DltStatus.RESOLVED) {
            throw new AlreadyResolvedDltException();
        }

        // 단일 원자적 쿼리로 재고 강제 복구
        int updatedRows = goodsRepository.increaseStockAtomically(dlt.getGoodsId(), dlt.getQuantity());
        if (updatedRows == 0) {
            throw new GoodsNotFoundException();
        }

        // DLT 해결 상태로 변경
        dlt.markAsResolved();
        deadLetterRepository.save(dlt); // 여기도 더티 체킹이 안 먹히므로 명시적 저장 필요

        log.info("🛠️ [Admin] DLT 수동 복구 및 Outbox 동기화 완료. DLT ID: {}", dltId);
    }
}