package promotion.serverA.service.dlt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import promotion.serverA.dto.response.DltResponse;
import promotion.serverA.entity.DeadLetter;
import promotion.serverA.entity.DltStatus;
import promotion.serverA.exception.AlreadyResolvedDltException;
import promotion.serverA.exception.DltNotFoundException;
import promotion.serverA.exception.GoodsNotFoundException;
import promotion.serverA.repository.DeadLetterRepository;
import promotion.serverA.repository.GoodsRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    private final DeadLetterRepository deadLetterRepository;
    private final GoodsRepository goodsRepository;

    // 💡 1. 격리 저장 (본 트랜잭션이 롤백되어도 이 기록은 남음)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDeadLetter(String orderId, Long goodsId, int quantity, String reason) {
        deadLetterRepository.save(DeadLetter.builder()
                                            .orderId(orderId)
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

    public DltResponse findDltByOrderId(String orderId) {
        DeadLetter dlt = deadLetterRepository.findByOrderId(orderId)
                                             .orElseThrow(DltNotFoundException::new);
        return new DltResponse(dlt.getId(), dlt.getOrderId(), dlt.getGoodsId(),
                dlt.getQuantity(), dlt.getReason(), dlt.getStatus().name());
    }

    public List<DltResponse> listUnresolved() {
        return deadLetterRepository.findAllByStatus(DltStatus.UNRESOLVED).stream()
                .map(dlt -> new DltResponse(dlt.getId(), dlt.getOrderId(), dlt.getGoodsId(),
                        dlt.getQuantity(), dlt.getReason(), dlt.getStatus().name()))
                .toList();
    }
}