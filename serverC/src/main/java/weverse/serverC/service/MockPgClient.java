package weverse.serverC.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import weverse.serverC.dto.PurchaseMessage;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MockPgClient implements PgClient {

    @Override
    @CircuitBreaker(name = "pgClientCb", fallbackMethod = "fallbackProcessPayments")
    public List<String> processPayments(List<PurchaseMessage> messages) {
        log.info("[MockPgClient] PG 결제 요청 처리 시작: 대상 건수 = {}", messages.size());
        List<String> failedTraceIds = new ArrayList<>();

        try { Thread.sleep(500);
        } catch (InterruptedException e) {
        }

        for (PurchaseMessage msg : messages) {
            if (Math.random() < 0.01) {
                log.warn("🚨 [MockPgClient] 결제 실패 시뮬레이션 당첨!: traceId={}", msg.traceId());
                failedTraceIds.add(msg.traceId());
            } else {
                log.info("✅ [MockPgClient] 결제 승인 성공: traceId={}", msg.traceId());
            }
        }
        return failedTraceIds;
    }

    // 💡 차단(Open) 상태일 때 실행될 Fallback 로직
    // 외부 PG사가 죽었다면, 굳이 기다리지 않고 "전체 결제 실패"로 즉시 처리하여 내 서버의 스레드를 보호합니다.
    public List<String> fallbackProcessPayments(List<PurchaseMessage> messages, Throwable t) {
        log.error("🚨 [Circuit Breaker OPEN] PG사 장애 감지! 결제 요청을 즉각 차단하고 전체 FAIL 처리합니다. 사유: {}", t.getMessage());

        // 들어온 모든 주문 건의 traceId를 실패 리스트로 만들어 반환 (빠른 실패)
        return messages.stream()
                       .map(PurchaseMessage::traceId)
                       .toList();
    }

    @Override
    public void cancelPayments(List<String> traceIds) {
        if (traceIds.isEmpty()) return;
        log.warn("🚨 PG사로 결제 강제 취소(망취소)를 요청했습니다. 대상: {}건", traceIds.size());
        try { Thread.sleep(200); } catch (InterruptedException e) {}
    }
}