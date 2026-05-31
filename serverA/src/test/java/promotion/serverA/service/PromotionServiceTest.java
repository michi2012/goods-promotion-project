package promotion.serverA.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import promotion.serverA.dto.PurchaseMessage;
import promotion.serverA.dto.StockSnapshotMessage;
import promotion.serverA.exception.DuplicateOrderException;
import promotion.serverA.exception.SoldOutException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @InjectMocks
    private PromotionService promotionService;

    @Mock
    private RedisStockService redisStockService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("API 수신: 이미 구매한 유저라면 DuplicateOrderException 예외가 발생한다.")
    void acceptPurchase_DuplicateUser_ThrowsException() {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(redisStockService.tryMarkUserPurchased(msg.userId(), msg.goodsId())).willReturn(false); // 중복 구매 판정

        // When & Then
        assertThatThrownBy(() -> promotionService.acceptPurchase(msg))
                .isInstanceOf(DuplicateOrderException.class);

        // 검증: 재고 차감 로직이나 Kafka 전송이 절대 호출되지 않아야 함
        verify(redisStockService, never()).reserveStock(anyLong(), anyInt());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("API 수신: 재고가 부족하면 SoldOutException 예외가 발생하고 유저 구매 플래그가 롤백된다.")
    void acceptPurchase_SoldOut_ThrowsException() {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(redisStockService.tryMarkUserPurchased(msg.userId(), msg.goodsId())).willReturn(true);
        given(redisStockService.reserveStock(msg.goodsId(), msg.quantity())).willReturn(-1L); // 재고 부족

        // When & Then
        assertThatThrownBy(() -> promotionService.acceptPurchase(msg))
                .isInstanceOf(SoldOutException.class);

        // 검증: 재고 부족 시 확보했던 유저 구매 플래그를 다시 해제해야 함
        verify(redisStockService, times(1)).releaseUserPurchase(msg.userId(), msg.goodsId());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("API 수신: Redis에 재고 키가 없으면 RuntimeException이 발생하고 유저 구매 플래그가 롤백된다.")
    void acceptPurchase_StockNotInitialized_ThrowsException() {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(redisStockService.tryMarkUserPurchased(msg.userId(), msg.goodsId())).willReturn(true);
        given(redisStockService.reserveStock(msg.goodsId(), msg.quantity())).willReturn(-2L); // Redis 키 없음

        // When & Then
        assertThatThrownBy(() -> promotionService.acceptPurchase(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("재고 정보 미초기화");

        // 검증: 키 없음 시에도 유저 구매 플래그가 롤백되어야 함
        verify(redisStockService, times(1)).releaseUserPurchase(msg.userId(), msg.goodsId());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("API 수신: Redis 검증 통과 및 Kafka 전송 성공 시 예외 없이 정상 종료된다.")
    void acceptPurchase_Success() throws Exception {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(redisStockService.tryMarkUserPurchased(msg.userId(), msg.goodsId())).willReturn(true);
        given(redisStockService.reserveStock(msg.goodsId(), msg.quantity())).willReturn(5L); // 재고 선점 성공
        given(objectMapper.writeValueAsString(any(PurchaseMessage.class))).willReturn("{\"payload\":\"test\"}");
        given(objectMapper.writeValueAsString(any(StockSnapshotMessage.class))).willReturn("{}");

        // Kafka Future 모킹 (정상 응답)
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("purchase_events"), eq(String.valueOf(msg.userId())), anyString())).willReturn(future);
        given(future.get(3, TimeUnit.SECONDS)).willReturn(mock(SendResult.class));

        // When & Then
        assertDoesNotThrow(() -> promotionService.acceptPurchase(msg));

        // 검증: 성공했으므로 롤백 메서드가 호출되지 않아야 함
        verify(redisStockService, never()).releaseStock(anyLong(), anyInt());
        verify(redisStockService, never()).releaseUserPurchase(anyLong(), anyLong());
    }

    @Test
    @DisplayName("API 수신: Kafka 전송 실패(타임아웃) 시 whenComplete 콜백에서 Redis 롤백이 진행된다.")
    void acceptPurchase_KafkaTimeout_RollbacksAndThrows() throws Exception {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(redisStockService.tryMarkUserPurchased(msg.userId(), msg.goodsId())).willReturn(true);
        given(redisStockService.reserveStock(msg.goodsId(), msg.quantity())).willReturn(5L);
        given(objectMapper.writeValueAsString(any(PurchaseMessage.class))).willReturn("{\"payload\":\"test\"}");
        given(objectMapper.writeValueAsString(any(StockSnapshotMessage.class))).willReturn("{}");

        // 이미 실패 완료된 Future → whenComplete 콜백이 동기적으로 즉시 실행됨
        CompletableFuture<SendResult<String, String>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Kafka Timeout"));
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(failedFuture);

        // When: 비동기 패턴이므로 acceptPurchase 자체는 예외 없이 반환
        assertDoesNotThrow(() -> promotionService.acceptPurchase(msg));

        // Then: whenComplete 콜백에서 롤백이 동기적으로 실행되었는지 검증
        verify(redisStockService, times(1)).releaseStock(msg.goodsId(), msg.quantity());
        verify(redisStockService, times(1)).releaseUserPurchase(msg.userId(), msg.goodsId());
    }

    @Test
    @DisplayName("API 수신: Kafka 전송 실패(시스템 오류) 시 whenComplete 콜백에서 Redis 롤백이 진행된다.")
    void acceptPurchase_KafkaException_RollbacksAndThrows() throws Exception {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(redisStockService.tryMarkUserPurchased(msg.userId(), msg.goodsId())).willReturn(true);
        given(redisStockService.reserveStock(msg.goodsId(), msg.quantity())).willReturn(5L);
        given(objectMapper.writeValueAsString(any(PurchaseMessage.class))).willReturn("{\"payload\":\"test\"}");
        given(objectMapper.writeValueAsString(any(StockSnapshotMessage.class))).willReturn("{}");

        // 이미 실패 완료된 Future → whenComplete 콜백이 동기적으로 즉시 실행됨
        CompletableFuture<SendResult<String, String>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Kafka Broker Down"));
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(failedFuture);

        // When: 비동기 패턴이므로 acceptPurchase 자체는 예외 없이 반환
        assertDoesNotThrow(() -> promotionService.acceptPurchase(msg));

        // Then: whenComplete 콜백에서 롤백이 동기적으로 실행되었는지 검증
        verify(redisStockService, times(1)).releaseStock(msg.goodsId(), msg.quantity());
        verify(redisStockService, times(1)).releaseUserPurchase(msg.userId(), msg.goodsId());
    }

    @Test
    @DisplayName("API 수신: 객체 직렬화 실패 시 롤백이 진행되고 RuntimeException이 발생한다.")
    void acceptPurchase_JsonProcessingException_RollbacksAndThrows() throws Exception {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(redisStockService.tryMarkUserPurchased(msg.userId(), msg.goodsId())).willReturn(true);
        given(redisStockService.reserveStock(msg.goodsId(), msg.quantity())).willReturn(5L);

        // 직렬화 실패 예외 모킹 (JsonProcessingException은 abstract이므로 mock으로 생성)
        JsonProcessingException jsonException = mock(JsonProcessingException.class);
        given(objectMapper.writeValueAsString(msg)).willThrow(jsonException);

        // When & Then
        assertThatThrownBy(() -> promotionService.acceptPurchase(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("직렬화 실패");

        // 검증: 직렬화 실패 시에도 롤백이 보장되어야 함
        verify(redisStockService, times(1)).releaseStock(msg.goodsId(), msg.quantity());
        verify(redisStockService, times(1)).releaseUserPurchase(msg.userId(), msg.goodsId());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString()); // 카프카 전송은 시도조차 안 함
    }

    // 헬퍼 메서드
    private PurchaseMessage createDummyMessage() {
        return new PurchaseMessage("trace-1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "test@test.com", "memo", "127.0.0.1");
    }
}