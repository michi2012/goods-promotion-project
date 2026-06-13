package aiops.aiops.router;

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

import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("CodebotClient 단위 테스트")
class CodebotClientTest {

    @Mock
    private DiscoveryClient discoveryClient;

    @Mock
    private ServiceInstance serviceInstance;

    private MockRestServiceServer server;
    private CodebotClient codebotClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        codebotClient = new CodebotClient(discoveryClient, builder);
    }

    @Test
    @DisplayName("codebot 인스턴스를 찾으면 조사 결과를 반환한다")
    void investigate_성공() {
        // given
        given(discoveryClient.getInstances("codebot")).willReturn(List.of(serviceInstance));
        given(serviceInstance.getUri()).willReturn(URI.create("http://localhost:8087"));

        server.expect(requestTo("http://localhost:8087/internal/investigations"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        { "result": "조사 완료: 원인은 ..." }
                        """, MediaType.APPLICATION_JSON));

        // when
        String result = codebotClient.investigate("thread-1", "PaymentService에서 NPE가 발생해요");

        // then
        assertThat(result).isEqualTo("조사 완료: 원인은 ...");
    }

    @Test
    @DisplayName("codebot 인스턴스가 없으면 안내 메시지를 반환한다")
    void investigate_인스턴스없음() {
        // given
        given(discoveryClient.getInstances("codebot")).willReturn(Collections.emptyList());

        // when
        String result = codebotClient.investigate("thread-1", "PaymentService에서 NPE가 발생해요");

        // then
        assertThat(result).contains("연결할 수 없");
    }

    @Test
    @DisplayName("HTTP 요청이 실패하면 오류 메시지를 반환한다")
    void investigate_HTTP실패() {
        // given
        given(discoveryClient.getInstances("codebot")).willReturn(List.of(serviceInstance));
        given(serviceInstance.getUri()).willReturn(URI.create("http://localhost:8087"));

        server.expect(requestTo("http://localhost:8087/internal/investigations"))
                .andRespond(withServerError());

        // when
        String result = codebotClient.investigate("thread-1", "PaymentService에서 NPE가 발생해요");

        // then
        assertThat(result).startsWith("코드 조사 요청 중 오류가 발생했습니다");
    }
}
