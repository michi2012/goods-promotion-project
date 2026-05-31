package promotion.serverB.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    @InjectMocks
    private OrderQueryService orderQueryService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final String orderId = "trace-1234";
    private final Long goodsId = 100L;

    @BeforeEach
    void setUp() {
        // 모든 테스트에서 opsForValue() 호출 시 mock 객체를 반환하도록 설정
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
    }

    @Test
    @DisplayName("주문 상태를 Redis에 정상적으로 업데이트한다")
    void updateOrderStatus_success() {
        // given
        String status = "PAID";
        String expectedKey = "order:view:" + orderId + ":status";

        // when
        orderQueryService.updateOrderStatus(orderId, status);

        // then
        verify(valueOperations).set(expectedKey, status);
    }

    @Test
    @DisplayName("조회된 주문 상태가 있으면 해당 값을 반환한다")
    void getOrderStatus_returns_status() {
        // given
        String expectedKey = "order:view:" + orderId + ":status";
        given(valueOperations.get(expectedKey)).willReturn("PAID");

        // when
        String status = orderQueryService.getOrderStatus(orderId);

        // then
        assertThat(status).isEqualTo("PAID");
    }

    @Test
    @DisplayName("조회된 주문 상태가 없으면 NOT_FOUND를 반환한다")
    void getOrderStatus_returns_NOT_FOUND_when_null() {
        // given
        String expectedKey = "order:view:" + orderId + ":status";
        given(valueOperations.get(expectedKey)).willReturn(null);

        // when
        String status = orderQueryService.getOrderStatus(orderId);

        // then
        assertThat(status).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("재고 뷰 데이터를 Redis에 정상적으로 업데이트한다")
    void updateStockView_success() {
        // given
        Long remainingStock = 45L;
        String expectedKey = "goods:view:stock:" + goodsId;

        // when
        orderQueryService.updateStockView(goodsId, remainingStock);

        // then
        verify(valueOperations).set(expectedKey, "45");
    }

    @Test
    @DisplayName("조회된 재고 뷰 데이터가 있으면 Long 타입으로 변환하여 반환한다")
    void getStockView_returns_stock() {
        // given
        String expectedKey = "goods:view:stock:" + goodsId;
        given(valueOperations.get(expectedKey)).willReturn("45");

        // when
        Long stock = orderQueryService.getStockView(goodsId);

        // then
        assertThat(stock).isEqualTo(45L);
    }

    @Test
    @DisplayName("조회된 재고 뷰 데이터가 없으면 0을 반환한다")
    void getStockView_returns_0_when_null() {
        // given
        String expectedKey = "goods:view:stock:" + goodsId;
        given(valueOperations.get(expectedKey)).willReturn(null);

        // when
        Long stock = orderQueryService.getStockView(goodsId);

        // then
        assertThat(stock).isEqualTo(0L);
    }
}