package mcp.mcp.agent;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AlertDeduplicationService {

    private static final Duration TTL = Duration.ofMinutes(30);
    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * fingerprint가 TTL 이내에 이미 처리된 알람이면 true 반환 (중복).
     * 신규 또는 만료된 경우 false 반환 후 현재 시각으로 등록.
     */
    public boolean isDuplicate(String fingerprint) {
        Instant now = Instant.now();
        seen.entrySet().removeIf(e -> now.isAfter(e.getValue().plus(TTL)));

        Instant firstSeen = seen.get(fingerprint);
        if (firstSeen != null) {
            return true;
        }
        seen.put(fingerprint, now);
        return false;
    }
}
