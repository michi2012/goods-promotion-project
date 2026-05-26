package mcp.mcp.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ActionApprovalService {

    private static final Duration TTL = Duration.ofHours(1);
    private final ConcurrentHashMap<String, PendingAction> pending = new ConcurrentHashMap<>();

    public record PendingAction(String id, String actionType, String params, String reason, Instant proposedAt) {}

    public String propose(String actionType, String params, String reason) {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> now.isAfter(e.getValue().proposedAt().plus(TTL)));

        String id = UUID.randomUUID().toString();
        pending.put(id, new PendingAction(id, actionType, params, reason, now));
        log.info("[Approval] 조치 제안 등록: id={}, type={}", id, actionType);
        return id;
    }

    public Optional<PendingAction> approve(String id) {
        PendingAction action = pending.remove(id);
        if (action == null) {
            log.warn("[Approval] 존재하지 않거나 만료된 승인 ID: {}", id);
            return Optional.empty();
        }
        // TODO: actionType별 실제 executor 연결 예정 (kubectl, kafka-admin 등)
        log.info("[Approval] 승인 완료: id={}, type={}, reason={}", action.id(), action.actionType(), action.reason());
        return Optional.of(action);
    }
}
