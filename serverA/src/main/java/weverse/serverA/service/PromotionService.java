package weverse.serverA.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.dto.PurchaseTask;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.exception.BusinessException;
import weverse.serverA.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import weverse.serverA.service.outbox.OutboxBatchService;
import weverse.serverA.service.outbox.OutboxProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionService {

    // Bounded Queue
    private final BlockingQueue<PurchaseTask> memoryQueue = new ArrayBlockingQueue<>(10000);

    private final OutboxProcessor outboxProcessor;
    private final OutboxRepository outboxRepository;
    private final OutboxBatchService outboxBatchService;

    // 1차 메모리 중복 방어용
    private final ConcurrentMap<Long, String> userCache = new ConcurrentHashMap<>();

    // A. API 수신 및 대기
    public CompletableFuture<ResponseEntity<String>> acceptPurchase(PurchaseMessage message) {
        CompletableFuture<ResponseEntity<String>> future = new CompletableFuture<>();
        if (!memoryQueue.offer(new PurchaseTask(message, future))) {
            future.complete(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("서버가 혼잡합니다."));
        }
        return future;
    }

    // B. Flusher 스레드 (0.1초마다 실행)
    @Scheduled(fixedDelay = 100)
    public void flushToOutbox() {
        if (memoryQueue.isEmpty()) return;

        List<PurchaseTask> tasks = new ArrayList<>();
        memoryQueue.drainTo(tasks, 500);

        try {
            // 💡 1. 분리된 서비스 호출 (이 안에서 트랜잭션이 작동)
            outboxBatchService.batchInsert(tasks);

            // 💡 2. 성공 시 대기 중이던 톰캣 스레드 500개에 202 응답
            tasks.forEach(task -> task.future().complete(ResponseEntity.accepted().body("접수 완료")));

        } catch (Exception e) {
            // DB가 롤백되었으므로 사용자들에게 500 에러를 줘서 스레드를 해방시킵니다.
            log.error("🚨 DB 벌크 인서트 실패. 500건 롤백 및 사용자 에러 응답 처리", e);
            tasks.forEach(task -> task.future().complete(
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("서버 일시적 오류로 접수에 실패했습니다.")
            ));
        }
    }

    // C. 비동기 비즈니스 워커 (재고 차감)
    @Scheduled(fixedDelay = 50)
    public void processPendingRequests() {
        List<RequestOutbox> pendings = outboxRepository.findByStatus(OutboxStatus.PENDING, PageRequest.of(0, 500));

        for (RequestOutbox outbox : pendings) {
            // 💡 1. 1차 메모리 방어막 (이벤트 끝날 때까지 유지)
            String existingTraceId = userCache.putIfAbsent(outbox.getUserId(), outbox.getTraceId());

            if (existingTraceId != null && !existingTraceId.equals(outbox.getTraceId())) {
                // 이미 캐시에 박제된 유저의 중복 요청 -> DB까지 안 가고 메모리에서 즉시 컷!
                outboxProcessor.markAsFailDirectly(outbox.getId());
                continue;
            }

            boolean isSuccess = false;

            try {
                // 2. 단일 원자적 쿼리로 검증 및 재고 차감 시도
                outboxProcessor.processSingleItem(outbox.getId());
                isSuccess = true; // 무사히 통과했다면 성공 플래그 ON

            } catch (BusinessException e) {
                log.warn("검증 실패 (품절 등) - traceId: {}, 사유: {}", outbox.getTraceId(), e.getMessage());
                // 품절 등의 비즈니스 실패 처리

            } catch (Exception e) {
                log.error("시스템 에러로 검증 영구 실패 처리 - traceId: {}", outbox.getTraceId(), e);
                outboxProcessor.markAsFailDirectly(outbox.getId());

            } finally {
                // 시스템 에러나 품절 등으로 '결제에 실패'해서 유저가 나중에 다시 시도해야 할 수도 있다면 캐시를 비워줍니다.
                if (!isSuccess) {
                    userCache.remove(outbox.getUserId());
                }
            }
        }
    }
}