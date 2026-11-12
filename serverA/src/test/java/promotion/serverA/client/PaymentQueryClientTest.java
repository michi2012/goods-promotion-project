package promotion.serverA.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentQueryClient 단위 테스트")
class PaymentQueryClientTest {

    @Mock
    private DiscoveryClient discoveryClient;

    @Mock
    private ServiceInstance serviceInstance;

    private MockRestServiceServer server;
    private PaymentQueryClient paymentQueryClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        paymentQueryClient = new PaymentQueryClient(discoveryClient, builder);

        given(discoveryClient.getInstances("serverC")).willReturn(List.of(serviceInstance));
        given(serviceInstance.isSecure()).willReturn(false);
        given(serviceInstance.getHost()).willReturn("localhost");
        given(serviceInstance.getPort()).willReturn(8082);
    }

    @Test
    @DisplayName("결제 상태가 PAID면 PAID를 반환한다")
    void checkPaymentStatus_Paid() {
        server.expect(requestTo("http://localhost:8082/api/v1/payments/order-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "orderId": "order-1", "status": "PAID" }
                        """, MediaType.APPLICATION_JSON));

        PaymentCheckStatus result = paymentQueryClient.checkPaymentStatus("order-1");

        assertThat(result).isEqualTo(PaymentCheckStatus.PAID);
    }

    @Test
    @DisplayName("결제 상태가 PAID가 아니면 NOT_PAID를 반환한다")
    void checkPaymentStatus_Failed() {
        server.expect(requestTo("http://localhost:8082/api/v1/payments/order-2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "orderId": "order-2", "status": "FAILED" }
                        """, MediaType.APPLICATION_JSON));

        PaymentCheckStatus result = paymentQueryClient.checkPaymentStatus("order-2");

        assertThat(result).isEqualTo(PaymentCheckStatus.NOT_PAID);
    }

    @Test
    @DisplayName("결제 내역이 없으면(404) NOT_PAID를 반환한다")
    void checkPaymentStatus_NotFound() {
        server.expect(requestTo("http://localhost:8082/api/v1/payments/order-3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        PaymentCheckStatus result = paymentQueryClient.checkPaymentStatus("order-3");

        assertThat(result).isEqualTo(PaymentCheckStatus.NOT_PAID);
    }

    @Test
    @DisplayName("조회 자체가 실패하면(서버 에러) UNKNOWN을 반환한다")
    void checkPaymentStatus_ServerError_ReturnsUnknown() {
        server.expect(requestTo("http://localhost:8082/api/v1/payments/order-4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        PaymentCheckStatus result = paymentQueryClient.checkPaymentStatus("order-4");

        assertThat(result).isEqualTo(PaymentCheckStatus.UNKNOWN);
    }
}
