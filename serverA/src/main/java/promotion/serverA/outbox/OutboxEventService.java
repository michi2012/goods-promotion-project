package promotion.serverA.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    // 반드시 호출자의 @Transactional 범위 안에서 호출해야 함
    public void save(String aggregateId, String topic, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxEventRepository.save(OutboxEvent.create(aggregateId, topic, json, currentTraceparent()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox 직렬화 실패: topic=" + topic, e);
        }
    }

    // MDC에서 Micrometer Tracing이 주입한 traceId/spanId로 W3C traceparent 조립
    private String currentTraceparent() {
        String traceId = MDC.get("traceId");
        String spanId  = MDC.get("spanId");
        if (traceId == null || spanId == null) return null;
        return "00-" + traceId + "-" + spanId + "-01";
    }
}
