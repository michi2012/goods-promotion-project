package weverse.serverA.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisStockServiceTest {

    private static final String REDIS_IMAGE = "redis:7.0-alpine";
    private static final int REDIS_PORT = 6379;

    // 도커를 통해 레디스 컨테이너 실행
    @Container
    private static final GenericContainer<?> REDIS_CONTAINER =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(REDIS_PORT);

    // 스프링 부트 설정에 실행된 컨테이너의 호스트와 포트 동적 매핑
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
        registry.add("spring.data.redis.port", REDIS_CONTAINER::getFirstMappedPort);
    }

    @Autowired
    private RedisStockService redisStockService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // 테스트 전 Redis 데이터 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("Lua 스크립트를 통해 정확히 요청한 수량만큼 재고가 차감된다.")
    void reserveStock_Success() {
        // Given
        Long goodsId = 1L;
        redisStockService.initStock(goodsId, 10);

        // When
        boolean result = redisStockService.reserveStock(goodsId, 3);

        // Then
        assertThat(result).isTrue();
        String currentStock = redisTemplate.opsForValue().get("goods:stock:" + goodsId);
        assertThat(currentStock).isEqualTo("7");
    }

    @Test
    @DisplayName("남은 재고보다 많은 수량을 차감 시도하면 실패(false)를 반환한다.")
    void reserveStock_Fail_NotEnoughStock() {
        // Given
        Long goodsId = 1L;
        redisStockService.initStock(goodsId, 2); // 재고 2개

        // When
        boolean result = redisStockService.reserveStock(goodsId, 3); // 3개 차감 시도

        // Then
        assertThat(result).isFalse();
    }
}