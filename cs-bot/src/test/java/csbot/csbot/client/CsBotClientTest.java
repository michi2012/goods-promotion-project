package csbot.csbot.client;

import csbot.csbot.client.dto.PaymentResponse;
import csbot.csbot.client.dto.UserProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("CsBotClient 단위 테스트")
class CsBotClientTest {

    @Mock
    private DiscoveryClient discoveryClient;

    @Mock
    private ServiceInstance serviceInstance;

    private MockRestServiceServer server;
    private CsBotClient csBotClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        csBotClient = new CsBotClient(discoveryClient, builder);
    }

    @Test
    @DisplayName("loginId로 숫자 userId를 조회한다")
    void resolveNumericUserId_성공() {
        given(discoveryClient.getInstances("user-service")).willReturn(List.of(serviceInstance));
        given(serviceInstance.isSecure()).willReturn(false);
        given(serviceInstance.getHost()).willReturn("localhost");
        given(serviceInstance.getPort()).willReturn(8086);

        server.expect(requestTo("http://localhost:8086/internal/api/users/test-uuid-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "id": 1, "userId": "test-uuid-123", "username": "Test User" }
                        """, MediaType.APPLICATION_JSON));

        Long result = csBotClient.resolveNumericUserId("test-uuid-123");

        assertThat(result).isEqualTo(1L);
    }

    @Test
    @DisplayName("userId로 결제/주문 내역을 조회한다")
    void getMyPayments_성공() {
        given(discoveryClient.getInstances("serverC")).willReturn(List.of(serviceInstance));
        given(serviceInstance.isSecure()).willReturn(false);
        given(serviceInstance.getHost()).willReturn("localhost");
        given(serviceInstance.getPort()).willReturn(8082);

        server.expect(requestTo("http://localhost:8082/api/v1/payments/users/1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "orderId": "order-1",
                            "userId": 1,
                            "goodsId": 10,
                            "quantity": 2,
                            "paymentMethod": "CARD",
                            "status": "SUCCESS",
                            "createdAt": "2026-06-01T10:00:00"
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<PaymentResponse> result = csBotClient.getMyPayments(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).orderId()).isEqualTo("order-1");
        assertThat(result.get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("userId와 loginId로 회원 정보를 조회한다")
    void getMyProfile_성공() {
        given(discoveryClient.getInstances("user-service")).willReturn(List.of(serviceInstance));
        given(serviceInstance.isSecure()).willReturn(false);
        given(serviceInstance.getHost()).willReturn("localhost");
        given(serviceInstance.getPort()).willReturn(8086);

        server.expect(requestTo("http://localhost:8086/api/users/1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-Id", "test-uuid-123"))
                .andRespond(withSuccess("""
                        { "id": 1, "userId": "test-uuid-123", "email": "test@example.com", "username": "Test User", "phoneNumber": "01012345678" }
                        """, MediaType.APPLICATION_JSON));

        UserProfileResponse result = csBotClient.getMyProfile(1L, "test-uuid-123");

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.email()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("서비스 인스턴스를 찾을 수 없으면 예외를 던진다")
    void getMyPayments_인스턴스없음() {
        given(discoveryClient.getInstances("serverC")).willReturn(Collections.emptyList());

        assertThatThrownBy(() -> csBotClient.getMyPayments(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serverC");
    }
}
