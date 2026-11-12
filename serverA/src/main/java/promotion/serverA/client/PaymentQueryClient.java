package promotion.serverA.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import promotion.serverA.client.dto.PaymentStatusResponse;

import java.util.List;

// Saga 상태(Redis)를 믿을 수 없는 상황(타임아웃 직전, Redis 상태 유실)에서
// 결제 서버(serverC)에 실제 결제 상태를 직접 확인하기 위한 클라이언트.
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentQueryClient {

    private final DiscoveryClient discoveryClient;
    private final @Qualifier("internalServiceClientBuilder") RestClient.Builder internalServiceClientBuilder;

    public PaymentCheckStatus checkPaymentStatus(String orderId) {
        try {
            PaymentStatusResponse response = clientFor("serverC")
                    .get()
                    .uri("/api/v1/payments/{orderId}", orderId)
                    .retrieve()
                    .body(PaymentStatusResponse.class);
            if (response == null) {
                return PaymentCheckStatus.UNKNOWN;
            }
            return "PAID".equals(response.status()) ? PaymentCheckStatus.PAID : PaymentCheckStatus.NOT_PAID;
        } catch (HttpClientErrorException.NotFound e) {
            return PaymentCheckStatus.NOT_PAID;
        } catch (Exception e) {
            log.warn("[PaymentQuery] 결제 상태 조회 실패: orderId={}, error={}", orderId, e.getMessage());
            return PaymentCheckStatus.UNKNOWN;
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
