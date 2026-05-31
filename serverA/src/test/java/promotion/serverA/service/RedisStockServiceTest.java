package promotion.serverA.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import promotion.serverA.support.AbstractSpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStockServiceTest extends AbstractSpringBootTest {

    @Autowired
    private RedisStockService redisStockService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("Lua 스크립트를 통해 정확히 요청한 수량만큼 재고가 차감되고 남은 재고를 반환한다.")
    void reserveStock_Success() {
        Long goodsId = 1L;
        redisStockService.initStock(goodsId, 10);

        Long result = redisStockService.reserveStock(goodsId, 3);

        assertThat(result).isEqualTo(7L);
        assertThat(redisTemplate.opsForValue().get("goods:stock:" + goodsId)).isEqualTo("7");
    }

    @Test
    @DisplayName("남은 재고보다 많은 수량을 차감 시도하면 -1을 반환한다.")
    void reserveStock_Fail_NotEnoughStock() {
        Long goodsId = 1L;
        redisStockService.initStock(goodsId, 2);

        Long result = redisStockService.reserveStock(goodsId, 3);

        assertThat(result).isEqualTo(-1L);
    }

    @Test
    @DisplayName("Redis에 재고 키가 없으면 -2를 반환한다.")
    void reserveStock_Fail_KeyNotFound() {
        Long result = redisStockService.reserveStock(99L, 1);

        assertThat(result).isEqualTo(-2L);
    }
}
