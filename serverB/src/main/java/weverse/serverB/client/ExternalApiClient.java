package weverse.serverB.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import weverse.serverB.dto.CompensationRequest;
import weverse.serverB.dto.PurchaseMessage;
import weverse.serverB.dto.ServerCResponse;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalApiClient {

    private final RestTemplate restTemplate;

    // Server C로 벌크 데이터 전송 (실패율 50% 이상 시 즉각 차단)
    @CircuitBreaker(name = "serverCClient", fallbackMethod = "fallbackServerC")
    public ResponseEntity<ServerCResponse> sendBulkToServerC(String url, List<PurchaseMessage> payloads) {
        return restTemplate.postForEntity(url, payloads, ServerCResponse.class);
    }

    // Server C 서킷 브레이커 Fallback
    public ResponseEntity<ServerCResponse> fallbackServerC(String url, List<PurchaseMessage> payloads, Throwable t) {
        log.error("🚨 [CB OPEN] Server C 장애 감지! 타임아웃 대기 없이 즉시 차단합니다. 사유: {}", t.getMessage());
        // 예외를 던져서 QueueToCWorker가 ACK를 하지 않고 빠져나가게 만듭니다. (나중에 복구 스케줄러가 재처리)
        throw new RuntimeException("Server C Circuit Breaker OPEN");
    }

    // Server A로 보상 트랜잭션(롤백) 지시
    @CircuitBreaker(name = "serverAClient", fallbackMethod = "fallbackServerA")
    public ResponseEntity<String> sendCompensationToServerA(String url, List<CompensationRequest> requests) {
        return restTemplate.postForEntity(url, requests, String.class);
    }

    // Server A 서킷 브레이커 Fallback
    public ResponseEntity<String> fallbackServerA(String url, List<CompensationRequest> requests, Throwable t) {
        log.error("🚨 [CB OPEN] Server A 장애 감지! 롤백 요청 즉시 실패 처리 후 재시도 큐로 직행. 사유: {}", t.getMessage());
        throw new RuntimeException("Server A Circuit Breaker OPEN");
    }
}