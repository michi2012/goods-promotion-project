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

    @Test
    @DisplayName("환불 완료용 구매 표시는 만료가 없어, 1시간짜리 구매 표시가 사라진 뒤에도 재구매를 막는다 (P-12)")
    void markUserPurchasedPermanently_blocksRepurchaseWithoutExpiry() {
        Long userId = 7L;
        Long goodsId = 1L;
        // 구매 후 1시간이 지나 원래 표시가 만료된 상황 — 키가 없다
        assertThat(redisTemplate.hasKey("user:purchase:7:1")).isFalse();

        redisStockService.markUserPurchasedPermanently(userId, goodsId);

        assertThat(redisTemplate.getExpire("user:purchase:7:1")).as("TTL -1 = 만료 없음").isEqualTo(-1L);
        assertThat(redisStockService.tryMarkUserPurchased(userId, goodsId)).as("재구매 차단").isFalse();
    }

    @Test
    @DisplayName("1시간짜리 구매 표시가 남아 있어도 환불 완료 시 만료 없는 표시로 바뀐다")
    void markUserPurchasedPermanently_removesExistingTtl() {
        redisStockService.tryMarkUserPurchased(7L, 1L);
        assertThat(redisTemplate.getExpire("user:purchase:7:1")).isPositive();

        redisStockService.markUserPurchasedPermanently(7L, 1L);

        assertThat(redisTemplate.getExpire("user:purchase:7:1")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("환불 재고 복구는 주문당 한 번만 재고를 올린다 — 재시도·재처리는 false, 방어 키는 만료 없음 (PR #56 리뷰 C2)")
    void restoreStockOnceForRefund_onlyFirstCallRestores() {
        redisStockService.initStock(1L, 3);

        assertThat(redisStockService.restoreStockOnceForRefund("order-1", 1L, 2)).isTrue();
        assertThat(redisStockService.restoreStockOnceForRefund("order-1", 1L, 2)).as("재시도").isFalse();

        assertThat(redisTemplate.opsForValue().get("goods:stock:1")).as("한 번만 +2").isEqualTo("5");
        assertThat(redisTemplate.getExpire("refund:restored:order-1")).as("TTL -1 = 만료 없음").isEqualTo(-1L);
    }

    @Test
    @DisplayName("방어 키와 재고 증가는 한 번에 일어난다 — 방어 키가 이미 있으면 재고는 그대로 (PR #56 리뷰 C1: 사이에서 실패해 덜 복구되는 일 없음)")
    void restoreStockOnceForRefund_isAtomicWithGuard() {
        redisStockService.initStock(1L, 3);
        redisTemplate.opsForValue().set("refund:restored:order-1", "1");

        assertThat(redisStockService.restoreStockOnceForRefund("order-1", 1L, 2)).isFalse();
        assertThat(redisTemplate.opsForValue().get("goods:stock:1")).isEqualTo("3");

        assertThat(redisStockService.restoreStockOnceForRefund("order-2", 1L, 2)).isTrue();
        assertThat(redisTemplate.opsForValue().get("goods:stock:1")).isEqualTo("5");
        assertThat(redisTemplate.hasKey("refund:restored:order-2")).isTrue();
    }
}
