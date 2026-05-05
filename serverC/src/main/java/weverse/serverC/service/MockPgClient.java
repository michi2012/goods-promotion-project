package weverse.serverC.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import weverse.serverC.dto.PurchaseMessage;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class MockPgClient implements PgClient {

    @Override
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

    @Override
    public void cancelPayments(List<String> traceIds) {
        if (traceIds.isEmpty()) return;
        log.warn("🚨 PG사로 결제 강제 취소(망취소)를 요청했습니다. 대상: {}건", traceIds.size());
        try { Thread.sleep(200); } catch (InterruptedException e) {}
    }
}