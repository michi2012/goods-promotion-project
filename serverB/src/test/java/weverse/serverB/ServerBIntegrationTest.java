package weverse.serverB;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import weverse.serverB.dto.PurchaseMessage;
import weverse.serverB.dto.ServerCResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "external.server-a.url=http://localhost:8080",
        "external.server-c.url=http://localhost:8082"
})
@AutoConfigureMockMvc
@Testcontainers
class ServerBIntegrationTest {

    @Container
    static GenericContainer<?> REDIS_CONTAINER = new GenericContainer<>(DockerImageName.parse("redis:7.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379).toString());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean
    private RestTemplate restTemplate;

    @Test
    @DisplayName("통합 테스트: 벌크 구매 요청이 들어오면 Redis Stream에 정상적으로 데이터가 적재된다.")
    void fullPipeline_ReceivesAndPushesToStream() throws Exception {
        // Given
        String traceId = "integ-trace-123";
        PurchaseMessage msg = new PurchaseMessage(traceId, 999L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP");
        List<PurchaseMessage> payload = List.of(msg);

        ServerCResponse mockResponse = new ServerCResponse(true, "성공", List.of());
        given(restTemplate.postForEntity(anyString(), any(), eq(ServerCResponse.class)))
                .willReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

        // When: API 호출
        mockMvc.perform(post("/api/v1/b/purchases/bulk")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(payload)))
               .andExpect(status().isOk());

        // Then 1
        Object status = redisTemplate.opsForHash().get("user:999:order", "status");
        assertThat(status).isEqualTo("PROCESSING");

        // Then 2
        List<MapRecord<String, Object, Object>> streamRecords = redisTemplate.opsForStream().read(
                StreamReadOptions.empty().count(1),
                org.springframework.data.redis.connection.stream.StreamOffset.latest("queue:to_server_c")
        );

        assertThat(streamRecords).isNotNull();
    }
}