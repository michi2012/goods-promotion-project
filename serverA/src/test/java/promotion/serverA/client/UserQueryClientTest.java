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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserQueryClient 단위 테스트")
class UserQueryClientTest {

    @Mock
    private DiscoveryClient discoveryClient;

    @Mock
    private ServiceInstance serviceInstance;

    private MockRestServiceServer server;
    private UserQueryClient userQueryClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        userQueryClient = new UserQueryClient(discoveryClient, builder);
    }

    private void givenUserServiceInstance() {
        given(discoveryClient.getInstances("user-service")).willReturn(List.of(serviceInstance));
        given(serviceInstance.isSecure()).willReturn(false);
        given(serviceInstance.getHost()).willReturn("localhost");
        given(serviceInstance.getPort()).willReturn(8086);
    }

    @Test
    @DisplayName("로그인 ID로 조회하면 숫자 사용자 ID를 돌려준다 (응답의 다른 필드는 무시)")
    void findUserId_found() {
        givenUserServiceInstance();
        server.expect(requestTo("http://localhost:8086/internal/api/users/login-abc"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        { "id": 42, "userId": "login-abc", "email": "a@b.com", "username": "kim" }
                        """, MediaType.APPLICATION_JSON));

        assertThat(userQueryClient.findUserId("login-abc")).isEqualTo(Optional.of(42L));
    }

    @Test
    @DisplayName("사용자가 없으면(404) empty를 돌려준다")
    void findUserId_notFound() {
        givenUserServiceInstance();
        server.expect(requestTo("http://localhost:8086/internal/api/users/nobody"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(userQueryClient.findUserId("nobody")).isEmpty();
    }

    @Test
    @DisplayName("user-service가 5xx면 예외를 던진다 (호출자가 조회 불가로 처리)")
    void findUserId_serverError() {
        givenUserServiceInstance();
        server.expect(requestTo("http://localhost:8086/internal/api/users/login-abc"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> userQueryClient.findUserId("login-abc")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("user-service 인스턴스를 찾지 못하면 예외를 던진다")
    void findUserId_noInstance() {
        given(discoveryClient.getInstances("user-service")).willReturn(List.of());

        assertThatThrownBy(() -> userQueryClient.findUserId("login-abc"))
                .isInstanceOf(IllegalStateException.class);
    }
}
