package weverse.serverA.service.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.exception.BusinessException;
import weverse.serverA.exception.DuplicateOrderException;
import weverse.serverA.exception.SoldOutException;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.EventNotifier;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OutboxProcessor {

    private final OutboxRepository outboxRepository;
    private final GoodsRepository goodsRepository;
    private final EventNotifier eventNotifier;

    // 💡 REQUIRES_NEW를 통해 무조건 독립된 새로운 트랜잭션을 시작함 (재시도 시 최신 DB 상태 조회용)
    // 💡 noRollbackFor 추가: 비즈니스 실패 시 상태 변경(FAIL)은 DB에 반영되어야 함
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = BusinessException.class)
    public void processSingleItem(Long outboxId) {
        RequestOutbox outbox = outboxRepository.findById(outboxId).orElseThrow();

        try {
            // 1. 중복 요청 2차 방어 (SUCCESS, PUBLISHING, SENT 모두 포함)
            boolean isAlreadyBought = outboxRepository.existsByUserIdAndStatusIn(
                    outbox.getUserId(),
                    List.of(OutboxStatus.SUCCESS, OutboxStatus.PUBLISHING, OutboxStatus.SENT)
            );

            if (isAlreadyBought) {
                throw new DuplicateOrderException();
            }

            // DB 레벨에서 락을 걸고 업데이트를 시도한 뒤, 성공한 행(Row)의 개수를 반환합니다.
            int updatedRows = goodsRepository.decreaseStockAtomically(outbox.getGoodsId(), outbox.getQuantity());
            if (updatedRows == 0) {
                eventNotifier.notifySoldOutToServerB(outbox.getGoodsId()); // 서버 B에 즉시 알림
                throw new SoldOutException(); // 예외를 던져서 catch 블록으로 넘깁니다.
            }

            int currentStock = goodsRepository.findStockById(outbox.getGoodsId());
            if (currentStock == 0) {
                eventNotifier.notifySoldOutToServerB(outbox.getGoodsId());
            }

            // 모든 로직 통과 시 성공 마킹
            outbox.markAsSuccess();
            outboxRepository.save(outbox); // 👈 [추가] 미아가 된 객체를 다시 저장하여 UPDATE 쿼리 유발!

        } catch (BusinessException e) {
            // 비즈니스 로직 실패(중복, 품절)는 FAIL 마킹
            outbox.markAsFail();
            outboxRepository.save(outbox); // 👈 [추가] 여기도 잊지 말고 추가!
            throw e;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsFailDirectly(Long outboxId) {
        outboxRepository.findById(outboxId).ifPresent(RequestOutbox::markAsFail);
    }
}