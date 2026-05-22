package weverse.serverA.service;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.exception.QueueFullException;
import weverse.serverA.exception.SoldOutException;
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.outbox.OutboxBatchService;
import weverse.serverA.service.outbox.OutboxProcessor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @InjectMocks private PromotionService promotionService;

    @Mock private GoodsService goodsService;
    @Mock private OutboxProcessor outboxProcessor;
    @Mock private OutboxRepository outboxRepository;
    @Mock private OutboxBatchService outboxBatchService;

    @BeforeEach
    void setUp() {
        Cache<Long, String> cache = (Cache<Long, String>) ReflectionTestUtils.getField(promotionService, "userCache");
        if (cache != null) cache.invalidateAll();
    }

    @Test
    @DisplayName("API 수신: 큐 진입 전 품절된 상품이면 즉시 SoldOutException이 발생한다.")
    void acceptPurchase_SoldOut_EarlyExit() {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(goodsService.isSoldOut(msg.goodsId())).willReturn(true);

        // When & Then
        assertThatThrownBy(() -> promotionService.acceptPurchase(msg))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    @DisplayName("API 수신: 큐가 꽉 찼을 경우 QueueFullException이 발생한다.")
    void acceptPurchase_QueueFull() {
        // Given
        BlockingQueue<PurchaseMessage> tinyQueue = new ArrayBlockingQueue<>(1);
        tinyQueue.offer(mock(PurchaseMessage.class));
        ReflectionTestUtils.setField(promotionService, "memoryQueue", tinyQueue);

        PurchaseMessage msg = createDummyMessage();
        given(goodsService.isSoldOut(msg.goodsId())).willReturn(false);

        // When & Then
        assertThatThrownBy(() -> promotionService.acceptPurchase(msg))
                .isInstanceOf(QueueFullException.class)
                .hasMessageContaining("대기열 합류에 실패");
    }

    @Test
    @DisplayName("큐 플러시: DB Batch Insert가 실패하더라도 예외를 먹고 스케줄러가 죽지 않아야 한다.")
    void flushToOutbox_FailsGracefully() {
        // Given
        PurchaseMessage msg = createDummyMessage();
        given(goodsService.isSoldOut(msg.goodsId())).willReturn(false);
        promotionService.acceptPurchase(msg);

        doThrow(new RuntimeException("DB 터짐")).when(outboxBatchService).batchInsert(anyList());

        // When & Then
        assertDoesNotThrow(() -> promotionService.flushToOutbox());

        BlockingQueue<PurchaseMessage> queue = (BlockingQueue<PurchaseMessage>) ReflectionTestUtils.getField(promotionService, "memoryQueue");
        assertThat(queue).isEmpty();
    }

    @Test
    @DisplayName("큐 플러시: 품절 확정된 상품 요청은 DB 삽입 없이 드랍하고, 정상 요청만 배치 삽입한다.")
    void flushToOutbox_DropsSoldOutTasks() {
        // Given: 품절 상품(goodsId=1)과 정상 상품(goodsId=2) 혼재
        PurchaseMessage soldOutMsg = new PurchaseMessage("trace-sold", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I");
        PurchaseMessage validMsg   = new PurchaseMessage("trace-valid", 2L, 2L, 1, "C", "A", "1", "0", "E", "M", "I");

        given(goodsService.isSoldOut(1L)).willReturn(true);
        given(goodsService.isSoldOut(2L)).willReturn(false);

        promotionService.acceptPurchase(validMsg);

        BlockingQueue<PurchaseMessage> queue = (BlockingQueue<PurchaseMessage>) ReflectionTestUtils.getField(promotionService, "memoryQueue");
        queue.offer(soldOutMsg);

        // When
        promotionService.flushToOutbox();

        // Then: validMsg만 batchInsert 호출
        verify(outboxBatchService).batchInsert(argThat(list ->
                list.size() == 1 &&
                        ((PurchaseMessage) list.get(0)).traceId().equals("trace-valid")
        ));
        assertThat(queue).isEmpty();
    }

    @Test
    @DisplayName("팬딩 처리: 품절 goodsId가 있으면 DB 조회 이전에 해당 PENDING 레코드를 bulk FAIL 처리한다.")
    void processPendingRequests_BulkFailsSoldOutGoods() {
        // Given
        given(goodsService.getSoldOutGoodsIds()).willReturn(Set.of(1L));
        given(outboxRepository.bulkFailPendingByGoodsIds(anyList())).willReturn(5);
        given(outboxRepository.findByStatus(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                .willReturn(List.of());

        // When
        promotionService.processPendingRequests();

        // Then
        var inOrder = inOrder(outboxRepository);
        inOrder.verify(outboxRepository).bulkFailPendingByGoodsIds(List.of(1L));
        inOrder.verify(outboxRepository).findByStatus(eq(OutboxStatus.PENDING), any(PageRequest.class));
        verify(outboxProcessor, never()).processSingleItem(any(RequestOutbox.class));
    }

    @Test
    @DisplayName("팬딩 처리: 품절 goodsId가 없으면 bulk FAIL 없이 바로 PENDING 조회한다.")
    void processPendingRequests_SkipsBulkFailWhenNoSoldOut() {
        // Given
        given(goodsService.getSoldOutGoodsIds()).willReturn(Set.of());
        given(outboxRepository.findByStatus(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                .willReturn(List.of());

        // When
        promotionService.processPendingRequests();

        // Then
        verify(outboxRepository, never()).bulkFailPendingByGoodsIds(anyList());
    }

    @Test
    @DisplayName("팬딩 처리: 메모리 캐시에 이미 등록된 유저의 다른 TraceId 요청이 오면 DB 접근 없이 즉시 FAIL 처리한다.")
    void processPendingRequests_MemoryDuplicateBlock() {
        // Given
        Cache<Long, String> cache = (Cache<Long, String>) ReflectionTestUtils.getField(promotionService, "userCache");
        cache.put(1L, "trace-old");

        RequestOutbox duplicateOutbox = RequestOutbox.builder().traceId("trace-new").userId(1L).build();
        ReflectionTestUtils.setField(duplicateOutbox, "id", 100L);

        given(outboxRepository.findByStatus(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                .willReturn(List.of(duplicateOutbox));

        // When
        promotionService.processPendingRequests();

        // Then
        verify(outboxProcessor).markAsFailDirectly(duplicateOutbox);
        verify(outboxProcessor, never()).processSingleItem(any(RequestOutbox.class));
    }

    private PurchaseMessage createDummyMessage() {
        return new PurchaseMessage("trace-1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP");
    }
}