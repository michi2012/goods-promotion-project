package codebot.codebot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("ObservabilityTools(codebot) 단위 테스트")
class ObservabilityToolsTest {

    private MockRestServiceServer lokiServer;
    private MockRestServiceServer tempoServer;
    private MockRestServiceServer prometheusServer;
    private MockRestServiceServer pyroscopeServer;
    private ObservabilityTools observabilityTools;

    @BeforeEach
    void setUp() {
        RestClient.Builder lokiBuilder = RestClient.builder().baseUrl("http://loki:3100");
        RestClient.Builder tempoBuilder = RestClient.builder().baseUrl("http://tempo:3200");
        RestClient.Builder prometheusBuilder = RestClient.builder().baseUrl("http://prometheus:9090");
        RestClient.Builder pyroscopeBuilder = RestClient.builder().baseUrl("http://pyroscope:4040");

        lokiServer = MockRestServiceServer.bindTo(lokiBuilder).build();
        tempoServer = MockRestServiceServer.bindTo(tempoBuilder).build();
        prometheusServer = MockRestServiceServer.bindTo(prometheusBuilder).build();
        pyroscopeServer = MockRestServiceServer.bindTo(pyroscopeBuilder).build();

        observabilityTools = new ObservabilityTools(
                lokiBuilder.build(), tempoBuilder.build(), prometheusBuilder.build(), pyroscopeBuilder.build());
    }

    @Test
    @DisplayName("Loki 로그 조회 성공 시 응답 본문을 반환한다")
    void queryLokiLogs_성공() {
        // given
        String responseJson = """
                { "status": "success", "data": { "result": [] } }
                """;
        lokiServer.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/loki/api/v1/query_range");
                    assertThat(request.getURI().getQuery())
                            .contains("app=\"server-a\"")
                            .contains("limit=50");
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when
        String result = observabilityTools.queryLokiLogs("server-a", "|= \"ERROR\"", 30);

        // then
        assertThat(result).isEqualTo(responseJson);
    }

    @Test
    @DisplayName("Loki 조회가 실패하면 스킵 안내 메시지를 반환한다")
    void queryLokiLogs_실패() {
        // given
        lokiServer.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // when
        String result = observabilityTools.queryLokiLogs("server-a", "", 30);

        // then
        assertThat(result).startsWith("Loki 조회 실패");
    }

    @Test
    @DisplayName("Tempo 트레이스 조회 성공 시 응답 본문을 반환한다")
    void queryTempoTrace_성공() {
        // given
        String traceId = "abc123def456";
        String responseJson = """
                { "batches": [] }
                """;
        tempoServer.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/api/traces/" + traceId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when
        String result = observabilityTools.queryTempoTrace(traceId);

        // then
        assertThat(result).isEqualTo(responseJson);
    }

    @Test
    @DisplayName("Tempo 조회가 실패하면 실패 메시지를 반환한다")
    void queryTempoTrace_실패() {
        // given
        tempoServer.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // when
        String result = observabilityTools.queryTempoTrace("abc123");

        // then
        assertThat(result).startsWith("Tempo 조회 실패:");
    }

    @Test
    @DisplayName("Pyroscope 핫스팟 조회 성공 시 자체 CPU 시간 기준 상위 메서드를 반환한다")
    void queryProfilerHotspots_성공() {
        // given
        String responseJson = """
                {
                  "flamebearer": {
                    "names": ["total", "com.example.OrderService.calculate"],
                    "levels": [
                      [0, 100, 0, 0],
                      [0, 100, 100, 1]
                    ],
                    "numTicks": 100
                  }
                }
                """;
        pyroscopeServer.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/pyroscope/render");
                    assertThat(request.getURI().getQuery())
                            .contains("service_name=\"server-a\"")
                            .contains("format=json");
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when
        String result = observabilityTools.queryProfilerHotspots("server-a", 10, 1);

        // then
        assertThat(result)
                .contains("프로파일 핫스팟 Top 1")
                .contains("100.00% | com.example.OrderService.calculate");
    }

    @Test
    @DisplayName("Pyroscope 조회가 실패하면 스킵 안내 메시지를 반환한다")
    void queryProfilerHotspots_실패() {
        // given
        pyroscopeServer.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // when
        String result = observabilityTools.queryProfilerHotspots("server-a", 10, 5);

        // then
        assertThat(result).startsWith("Pyroscope 핫스팟 조회 실패");
    }

    @Test
    @DisplayName("Prometheus 메트릭 조회 성공 시 응답 본문을 반환한다")
    void queryPrometheusMetrics_성공() {
        // given
        String responseJson = """
                { "status": "success", "data": { "result": [] } }
                """;
        prometheusServer.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/api/v1/query");
                    assertThat(request.getURI().getQuery()).contains("up");
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when
        String result = observabilityTools.queryPrometheusMetrics("up");

        // then
        assertThat(result).isEqualTo(responseJson);
    }

    @Test
    @DisplayName("Prometheus 조회가 실패하면 실패 메시지를 반환한다")
    void queryPrometheusMetrics_실패() {
        // given
        prometheusServer.expect(request -> {})
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        // when
        String result = observabilityTools.queryPrometheusMetrics("up");

        // then
        assertThat(result).startsWith("Prometheus 조회 실패:");
    }
}
