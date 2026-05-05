package weverse.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.request.CompensationRequest;
import weverse.serverA.exception.GoodsNotFoundException;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.dlt.DeadLetterService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompensationService {

    private final GoodsRepository goodsRepository;
    private final OutboxRepository outboxRepository; // 💡 주입 추가
    private final DeadLetterService deadLetterService;

    @Transactional
    public void compensate(List<CompensationRequest> requests) {
        for (CompensationRequest req : requests) {
            try {
                // 💡 1. [멱등성 방어선] 아웃박스 상태를 원자적으로 COMPENSATED로 변경
                int outboxUpdatedRows = outboxRepository.markAsCompensatedAtomically(req.traceId());

                // 💡 2. 업데이트된 행이 0개라면? = 누군가 이미 COMPENSATED로 바꿈 (중복 메시지)
                if (outboxUpdatedRows == 0) {
                    log.warn("🛡️ [멱등성 방어] 이미 보상 처리된 요청입니다. (중복 스킵) TraceId: {}", req.traceId());
                    continue; // 재고를 복구하지 않고 즉시 다음 요청으로 넘어감
                }

                // 3. 중복이 아님이 완벽히 증명되었을 때만 재고 복구(원자적 증가) 실행
                int goodsUpdatedRows = goodsRepository.increaseStockAtomically(req.goodsId(), req.quantity());

                if (goodsUpdatedRows == 0) {
                    throw new GoodsNotFoundException();
                }

                log.info("⏪ [보상 완료] 재고 복구됨. TraceId: {}, 사유: {}", req.traceId(), req.reason());

            } catch (Exception e) {
                log.error("❌ [보상 실패] DLT 이관. TraceId: {}", req.traceId(), e);
                deadLetterService.saveDeadLetter(req.traceId(), req.goodsId(), req.quantity(), "보상 실패: " + e.getMessage());
            }
        }
    }
}