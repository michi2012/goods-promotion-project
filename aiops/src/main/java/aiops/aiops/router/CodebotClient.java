package aiops.aiops.router;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodebotClient {

    private final DiscoveryClient discoveryClient;
    private final @Qualifier("internalServiceClientBuilder") RestClient.Builder internalServiceClientBuilder;

    public String investigate(String conversationId, String message) {
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances("codebot");
            if (instances.isEmpty()) {
                log.warn("[CodebotClient] codebot 인스턴스를 찾을 수 없습니다.");
                return "codebot 서비스에 연결할 수 없어 코드 조사를 진행하지 못했습니다.";
            }

            String baseUrl = instances.get(0).getUri().toString();
            InvestigationResponse response = internalServiceClientBuilder.clone()
                    .baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri("/internal/investigations")
                    .body(new InvestigationRequest(conversationId, message))
                    .retrieve()
                    .body(InvestigationResponse.class);

            return response.result();
        } catch (Exception e) {
            log.error("[CodebotClient] 조사 요청 실패: {}", e.getMessage());
            return "코드 조사 요청 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    private record InvestigationRequest(String conversationId, String message) {
    }

    private record InvestigationResponse(String result) {
    }
}
