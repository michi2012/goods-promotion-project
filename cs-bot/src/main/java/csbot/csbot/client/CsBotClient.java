package csbot.csbot.client;

import csbot.csbot.client.dto.DltResponse;
import csbot.csbot.client.dto.GoodsResponse;
import csbot.csbot.client.dto.InternalUserResponse;
import csbot.csbot.client.dto.OrderStatusResponse;
import csbot.csbot.client.dto.PaymentResponse;
import csbot.csbot.client.dto.UserProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CsBotClient {

    private final DiscoveryClient discoveryClient;
    private final @Qualifier("internalServiceClientBuilder") RestClient.Builder internalServiceClientBuilder;

    public Long resolveNumericUserId(String loginId) {
        InternalUserResponse response = clientFor("user-service")
                .get()
                .uri("/internal/api/users/{userId}", loginId)
                .retrieve()
                .body(InternalUserResponse.class);
        return response.id();
    }

    public List<PaymentResponse> getMyPayments(Long userId) {
        List<PaymentResponse> response = clientFor("serverC")
                .get()
                .uri("/api/v1/payments/users/{userId}", userId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<PaymentResponse>>() {
                });
        return response == null ? List.of() : response;
    }

    public UserProfileResponse getMyProfile(Long userId, String loginId) {
        return clientFor("user-service")
                .get()
                .uri("/api/users/{id}", userId)
                .header("X-User-Id", loginId)
                .retrieve()
                .body(UserProfileResponse.class);
    }

    public GoodsResponse getGoodsInfo(Long goodsId) {
        return clientFor("serverA")
                .get()
                .uri("/api/v1/goods/{goodsId}", goodsId)
                .retrieve()
                .body(GoodsResponse.class);
    }

    public OrderStatusResponse getOrderStatus(String orderId) {
        return clientFor("serverB")
                .get()
                .uri("/api/v1/orders/{orderId}/status", orderId)
                .retrieve()
                .body(OrderStatusResponse.class);
    }

    public DltResponse getDltByOrderId(String orderId) {
        return clientFor("serverA")
                .get()
                .uri("/api/v1/admin/dlt/orders/{orderId}", orderId)
                .retrieve()
                .body(DltResponse.class);
    }

    public void retryDlt(Long dltId) {
        clientFor("serverA")
                .post()
                .uri("/api/v1/admin/dlt/{dltId}/retry", dltId)
                .retrieve()
                .toBodilessEntity();
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
