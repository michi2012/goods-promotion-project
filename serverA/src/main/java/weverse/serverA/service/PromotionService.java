package weverse.serverA.service;

import jakarta.annotation.PreDestroy;
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
import java.util.Comparator;
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

    // A. API 수신 및 즉시 응답
    public ResponseEntity<String> acceptPurchase(PurchaseMessage message) {
        if (!memoryQueue.offer(new PurchaseTask(message, null))) {
            log.warn("[큐 진입 실패] 서버 과부하 | TraceId: {}", message.traceId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("서버가 혼잡합니다.");
        }

        //  큐에 들어갔다면 DB 저장을 기다리지 않고 즉시 응답
        return ResponseEntity.accepted().body("접수 대기 중입니다.");
    }

    // B. Flusher 스레드 (0.1초마다 실행)
    @Scheduled(fixedDelay = 100)
    public void flushToOutbox() {
        if (memoryQueue.isEmpty()) return;

        List<PurchaseTask> tasks = new ArrayList<>();
        memoryQueue.drainTo(tasks, 500);

        // DB 데드락 방지를 위해 Unique Index(traceId) 기준으로 정렬
        tasks.sort(Comparator.comparing(task -> task.message().traceId()));

        try {
            outboxBatchService.batchInsert(tasks);
            log.info("[Outbox 배치 성공] 처리 완료: {}건", tasks.size());

        } catch (Exception e) {
            log.error("[Outbox 배치 실패] 🚨 DB 인서트 에러! 데이터 유실 방지를 위해 Fallback 파일에 기록합니다. 사유: {}", e.getMessage());

            // 💡 유실 방지: DB가 죽었다면 파일(로그)로 남겨서 나중에 수동으로 복구(Retry)할 수 있게 격리합니다. (DLQ 패턴)
            fallbackToLogFile(tasks);
        }
    }

    // C. 비동기 비즈니스 워커 (재고 차감)
    @Scheduled(fixedDelay = 1000)
    public void processPendingRequests() {
        List<RequestOutbox> pendings = outboxRepository.findByStatus(OutboxStatus.PENDING, PageRequest.of(0, 500));
        if (pendings.isEmpty()) return;

        log.info("[재고 차감 워커 시작] PENDING 데이터 조회 건수: {}건", pendings.size());

        for (RequestOutbox outbox : pendings) {
            // 💡 1. 1차 메모리 방어막 (이벤트 끝날 때까지 유지)
            String existingTraceId = userCache.putIfAbsent(outbox.getUserId(), outbox.getTraceId());

            if (existingTraceId != null && !existingTraceId.equals(outbox.getTraceId())) {
                // 이미 캐시에 박제된 유저의 중복 요청 -> DB까지 안 가고 메모리에서 즉시 컷
                log.warn("[중복 요청 차단] 메모리 캐시 탐지 | UserId: {} | 기존Trace: {} | 신규Trace: {}",
                        outbox.getUserId(), existingTraceId, outbox.getTraceId());
                outboxProcessor.markAsFailDirectly(outbox.getId());
                continue;
            }

            boolean isSuccess = false;

            try {
                // 2. 단일 원자적 쿼리로 검증 및 재고 차감 시도
                outboxProcessor.processSingleItem(outbox.getId());
                isSuccess = true; // 무사히 통과했다면 성공 플래그 ON
                log.info("[재고 차감 성공] TraceId: {} | UserId: {} | GoodsId: {}",
                        outbox.getTraceId(), outbox.getUserId(), outbox.getGoodsId());

            } catch (BusinessException e) {
                log.warn("[재고 차감 거절] 비즈니스 로직 실패 | TraceId: {} | 사유: {}", outbox.getTraceId(), e.getMessage());

            } catch (Exception e) {
                log.error("[재고 차감 실패] 🚨 시스템 예외 발생 | TraceId: {} | 메시지: {}", outbox.getTraceId(), e.getMessage(), e);
                outboxProcessor.markAsFailDirectly(outbox.getId());

            } finally {
                // 시스템 에러나 품절 등으로 '결제에 실패'해서 유저가 나중에 다시 시도해야 할 수도 있다면 캐시를 비워줍니다.
                if (!isSuccess) {
                    userCache.remove(outbox.getUserId());
                    log.debug("[캐시 롤백] 실패 처리로 인한 메모리 캐시 삭제 | UserId: {}", outbox.getUserId());
                }
            }
        }
    }

    @PreDestroy
    public void tearDown() {
        if (memoryQueue.isEmpty()) {
            log.info("[Shutdown] 큐가 비어있어 안전하게 종료합니다.");
            return;
        }

        log.info("[Shutdown] 서버 종료 감지: 메모리 큐의 남은 데이터({})를 DB로 플러시합니다.", memoryQueue.size());

        List<PurchaseTask> remainingTasks = new ArrayList<>();

        try {
            // 큐의 데이터를 리스트로 이동
            memoryQueue.drainTo(remainingTasks);

            if (!remainingTasks.isEmpty()) {
                // DB 연결이 살아있는지 확인하며 인서트 시도
                outboxBatchService.batchInsert(remainingTasks);
                log.info("[Shutdown] 큐 플러시 완료: {}건 저장됨.", remainingTasks.size());
            }
        } catch (Exception e) {
            // DB가 이미 닫혔더라도 여기서 파일로 기록(DLQ)하여 유실을 방지합니다.
            log.error("[Shutdown] 🚨 종료 중 DB 저장 실패! Fallback 파일 기록을 시작합니다. 사유: {}", e.getMessage());
            fallbackToLogFile(remainingTasks);
        }
    }

    // 💡 별도의 파일이나 로그로 실패 데이터를 안전하게 백업
    private void fallbackToLogFile(List<PurchaseTask> failedTasks) {
        for (PurchaseTask task : failedTasks) {
            PurchaseMessage msg = task.message();
            // 현업에서는 SLF4J의 별도 로거(Appender)를 만들어 failed-orders.log 같은 파일에만 JSON 형태로 예쁘게 기록합니다.
            log.error("DEAD_LETTER_LOG | TraceId: {} | UserId: {} | GoodsId: {} | Payload: {}",
                    msg.traceId(), msg.userId(), msg.goodsId(), msg);
        }
    }

}