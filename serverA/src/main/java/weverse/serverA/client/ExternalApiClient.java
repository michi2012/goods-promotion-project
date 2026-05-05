package weverse.serverA.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import weverse.serverA.dto.PurchaseMessage;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalApiClient {

    private final RestTemplate restTemplate;

    @CircuitBreaker(name = "serverBClient", fallbackMethod = "fallbackSoldOutEvent")
    public void sendSoldOutEvent(String url) {
        restTemplate.postForEntity(url, null, String.class);
    }

    public void fallbackSoldOutEvent(String url, Throwable t) {
        log.error("🚨 [CB OPEN] Server B 장애 감지! 품절 알림 전송을 즉각 차단합니다.");
        throw new RuntimeException("Server B Circuit Breaker OPEN (Sold Out)");
    }

    @CircuitBreaker(name = "serverBClient")
    public ResponseEntity<String> pushBulkToServerB(String url, List<PurchaseMessage> messages) {
        return restTemplate.postForEntity(url, messages, String.class);
    }

    @CircuitBreaker(name = "serverBClient")
    public boolean checkPaymentStatusAtServerC(String traceId) {
        try {
            // Server C의 내부 조회용 엔드포인트 호출
            String url = "http://server-c:8082/api/v1/internal/payments/" + traceId;
            ResponseEntity<Boolean> response = restTemplate.getForEntity(url, Boolean.class);

            return Boolean.TRUE.equals(response.getBody());
        } catch (Exception e) {
            // 💡 404 에러 등이 발생하면 결제가 없는 것으로 간주
            log.warn("⚠️ [Server C 조회 실패] 결제 내역이 없거나 통신 장애입니다. TraceId: {}", traceId);
            return false;
        }
    }

}