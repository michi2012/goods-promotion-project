package aiops.aiops.scheduler;

import aiops.aiops.tools.KubernetesTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("CanaryRolloutScheduler 단위 테스트")
class CanaryRolloutSchedulerTest {

    @Mock
    private KubernetesTools kubernetesTools;

    private MockRestServiceServer server;
    private CanaryRolloutScheduler scheduler;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient prometheusClient = builder.build();

        scheduler = new CanaryRolloutScheduler(
                kubernetesTools,
                prometheusClient,
                new ObjectMapper(),
                List.of("server-a"),
                List.of(10, 25, 50, 100),
                2,
                5.0,
                0.05);
    }

    private String prometheusValue(double value) {
        return String.format("""
                {"status":"success","data":{"resultType":"vector","result":[{"metric":{},"value":[1700000000,"%s"]}]}}
                """, value);
    }

    @Test
    @DisplayName("v2 weight=0이면 스킵하고 proposeTrafficShift를 호출하지 않는다")
    void checkService_v2weight0_스킵() {
        given(kubernetesTools.getCanaryWeight("server-a")).willReturn(0);

        scheduler.checkService("server-a");

        verify(kubernetesTools, never()).proposeTrafficShift(anyString(), anyInt(), anyInt(), anyString());
        assertThat(scheduler.getStates()).doesNotContainKey("server-a");
        server.verify();
    }

    @Test
    @DisplayName("v2 weight=100이면 스킵하고 proposeTrafficShift를 호출하지 않는다")
    void checkService_v2weight100_스킵() {
        given(kubernetesTools.getCanaryWeight("server-a")).willReturn(100);

        scheduler.checkService("server-a");

        verify(kubernetesTools, never()).proposeTrafficShift(anyString(), anyInt(), anyInt(), anyString());
        assertThat(scheduler.getStates()).doesNotContainKey("server-a");
        server.verify();
    }

    @Test
    @DisplayName("v2 요청률이 가드값 미만이면 판단을 보류하고 연속 정상 카운터를 증가시키지 않는다")
    void checkService_요청률부족_카운터불변() {
        given(kubernetesTools.getCanaryWeight("server-a")).willReturn(10);
        server.expect(method(HttpMethod.GET))
                .andRespond(withSuccess(prometheusValue(0.01), MediaType.APPLICATION_JSON));

        scheduler.checkService("server-a");

        verify(kubernetesTools, never()).proposeTrafficShift(anyString(), anyInt(), anyInt(), anyString());
        assertThat(scheduler.getStates().get("server-a").consecutiveHealthyCount()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("v2 5xx 비율이 임계값을 초과하면 연속 정상 카운터를 리셋한다")
    void checkService_에러율초과_카운터리셋() {
        given(kubernetesTools.getCanaryWeight("server-a")).willReturn(10);

        // MockRestServiceServer는 요청이 시작된 후 expectation 추가를 허용하지 않으므로 모두 선언 후 호출한다.
        // 1차: 정상(요청률 1.0, 에러율 0%) / 2차: 에러율 10% > 5%
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(1.0), MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(0.0), MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(1.0), MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(10.0), MediaType.APPLICATION_JSON));

        scheduler.checkService("server-a");
        assertThat(scheduler.getStates().get("server-a").consecutiveHealthyCount()).isEqualTo(1);

        scheduler.checkService("server-a");
        assertThat(scheduler.getStates().get("server-a").consecutiveHealthyCount()).isZero();

        verify(kubernetesTools, never()).proposeTrafficShift(anyString(), anyInt(), anyInt(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("정상 상태가 healthyChecksRequired회 연속되면 다음 단계 승급을 제안하고, 같은 단계로는 재제안하지 않는다")
    void checkService_정상N회_승급제안_쿨다운() {
        given(kubernetesTools.getCanaryWeight("server-a")).willReturn(10);

        // 4회 호출 x (요청률, 에러율) = 8개 expectation, 모두 정상(요청률 1.0, 에러율 0%)
        for (int i = 0; i < 4; i++) {
            server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(1.0), MediaType.APPLICATION_JSON));
            server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(0.0), MediaType.APPLICATION_JSON));
        }

        // 1차: 카운터 1, 아직 제안 안 함
        scheduler.checkService("server-a");
        verify(kubernetesTools, never()).proposeTrafficShift(anyString(), anyInt(), anyInt(), anyString());

        // 2차: 카운터 2 == required → 25%로 승급 제안
        scheduler.checkService("server-a");
        verify(kubernetesTools, times(1)).proposeTrafficShift(eq("server-a"), eq(75), eq(25), anyString());

        // 3차, 4차: weight 변경 전(승인 대기 중) 다시 정상 N회 반복 → 동일 단계(25%) 재제안 안 됨
        scheduler.checkService("server-a");
        scheduler.checkService("server-a");

        verify(kubernetesTools, times(1)).proposeTrafficShift(anyString(), anyInt(), anyInt(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("외부에서 v2 weight가 변경되면 상태를 리셋한다")
    void checkService_weight외부변경시_상태리셋() {
        given(kubernetesTools.getCanaryWeight("server-a"))
                .willReturn(10)
                .willReturn(25);

        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(1.0), MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(0.0), MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(1.0), MediaType.APPLICATION_JSON));
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(prometheusValue(0.0), MediaType.APPLICATION_JSON));

        scheduler.checkService("server-a");
        assertThat(scheduler.getStates().get("server-a").consecutiveHealthyCount()).isEqualTo(1);

        // 외부(수동 승인 등)에서 weight가 10 -> 25로 변경됨
        scheduler.checkService("server-a");

        CanaryRolloutScheduler.CanaryState state = scheduler.getStates().get("server-a");
        assertThat(state.lastWeight()).isEqualTo(25);
        assertThat(state.consecutiveHealthyCount()).isEqualTo(1);
        server.verify();
    }
}
