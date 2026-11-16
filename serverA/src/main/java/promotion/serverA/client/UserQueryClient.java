package promotion.serverA.client;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import promotion.serverA.client.dto.InternalUserResponse;

import java.util.List;
import java.util.Optional;

// 환불 요청의 본인 확인용: 로그인 ID(X-User-Id)를 user-service에 물어 숫자 사용자 ID(orders.user_id)로 바꾼다.
// (docs/specs/user-refund.md 결정 기록 "본인 확인 방식")
@Component
@RequiredArgsConstructor
public class UserQueryClient {

    private final DiscoveryClient discoveryClient;
    private final @Qualifier("internalServiceClientBuilder") RestClient.Builder internalServiceClientBuilder;

    /**
     * @return 숫자 사용자 ID. 해당 로그인 ID의 사용자가 없으면(404) empty.
     * @throws RuntimeException user-service를 찾지 못하거나 응답이 비정상이면 (호출자가 "조회 불가"로 처리)
     */
    public Optional<Long> findUserId(String loginId) {
        try {
            InternalUserResponse response = clientFor("user-service")
                    .get()
                    .uri("/internal/api/users/{loginId}", loginId)
                    .retrieve()
                    .body(InternalUserResponse.class);
            if (response == null || response.id() == null) {
                throw new IllegalStateException("user-service 응답에 사용자 ID가 없습니다.");
            }
            return Optional.of(response.id());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    private RestClient clientFor(String serviceName) {
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
        if (instances.isEmpty()) {
            throw new IllegalStateException(serviceName + " 서비스 인스턴스를 찾을 수 없습니다.");
        }
        ServiceInstance instance = instances.get(0);
        String baseUrl = instance.isSecure()
                ? "https://" + instance.getHost() + ":" + instance.getPort()
                : "http://" + instance.getHost() + ":" + instance.getPort();
        return internalServiceClientBuilder.clone().baseUrl(baseUrl).build();
    }
}
