package weverse.serverB;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import weverse.serverB.service.PipelineService;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

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
    @Autowired private PipelineService pipelineService;

    @MockitoBean
    private RestTemplate restTemplate;

    // 매 테스트가 끝날 때마다 Redis를 초기화하여 테스트 간 간섭을 막습니다.
    @AfterEach
    void tearDown() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("멱등성 검증: 동일한 traceId가 여러 번 들어와도 최초 1회만 처리되고 나머지는 무시된다.")
    void processBulkData_Idempotency() {
        // Given: 완전히 동일한 traceId를 가진 메시지 3개 생성
        String traceId = "duplicate-trace";
        PurchaseMessage msg = new PurchaseMessage(traceId, 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        List<PurchaseMessage> messages = List.of(msg, msg, msg);

        // When
        pipelineService.processBulkData(messages);

        // Then
        // 1. 유저 상태는 정상적으로 변경되어야 함
        Object status = redisTemplate.opsForHash().get("user:1:order", "status");
        assertThat(status).isEqualTo("PROCESSING");

        // 2. 스트림 큐에는 3개가 아닌 딱 1개의 메시지만 적재되어야 함
        List<MapRecord<String, Object, Object>> streamRecords = redisTemplate.opsForStream().read(
                StreamReadOptions.empty(),
                StreamOffset.fromStart("queue:to_server_c")
        );
        assertThat(streamRecords).hasSize(1);
    }

    @Test
    @DisplayName("동시성 제어: 100개의 스레드가 0.001초 차이로 동일한 traceId 처리를 시도해도 단 1번만 성공한다.")
    void processBulkData_ConcurrencyCheck() throws InterruptedException {
        // Given
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        String traceId = "race-condition-trace";
        PurchaseMessage msg = new PurchaseMessage(traceId, 99L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        List<PurchaseMessage> singleMessageList = List.of(msg);

        // When: 100개의 스레드가 동시에 PipelineService를 공격
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    pipelineService.processBulkData(singleMessageList);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(); // 모든 스레드가 끝날 때까지 대기

        // Then
        // 1. 멱등성 키가 정상적으로 하나만 생성되었는지 확인
        String idempotencyKey = redisTemplate.opsForValue().get("trace:" + traceId);
        assertThat(idempotencyKey).isEqualTo("OK");

        // 2. 가장 중요한 검증: 스트림 큐에 중복 데이터가 들어가지 않고 정확히 1개만 있는지 확인
        List<MapRecord<String, Object, Object>> streamRecords = redisTemplate.opsForStream().read(
                StreamReadOptions.empty(),
                StreamOffset.fromStart("queue:to_server_c")
        );

        assertThat(streamRecords).hasSize(1);
    }
}