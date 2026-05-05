package weverse.serverA.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.dto.PurchaseTask;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.exception.QueueFullException;
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.outbox.OutboxBatchService;
import weverse.serverA.service.outbox.OutboxProcessor;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromotionServiceTest {

    @InjectMocks private PromotionService promotionService;
    @Mock private OutboxProcessor outboxProcessor;
    @Mock private OutboxRepository outboxRepository;
    @Mock private OutboxBatchService outboxBatchService;

    @BeforeEach
    void setUp() {
        ConcurrentMap<Long, String> cache = (ConcurrentMap<Long, String>) ReflectionTestUtils.getField(promotionService, "userCache");
        if (cache != null) cache.clear();
    }

    @Test
    @DisplayName("API 수신: 큐가 꽉 찼을 경우 QueueFullException이 발생한다.")
    void acceptPurchase_QueueFull() {
        // Given
        // 딱 1자리만 있는 큐를 만들고 미리 채워둠
        BlockingQueue<PurchaseTask> tinyQueue = new ArrayBlockingQueue<>(1);
        tinyQueue.offer(new PurchaseTask(mock(PurchaseMessage.class), null));
        ReflectionTestUtils.setField(promotionService, "memoryQueue", tinyQueue);

        // When & Then
        // ResponseEntity를 반환받는 대신, 특정 예외가 던져지는지 검증
        assertThatThrownBy(() -> promotionService.acceptPurchase(mock(PurchaseMessage.class)))
                .isInstanceOf(QueueFullException.class)
                .hasMessageContaining("대기열 합류에 실패했습니다");
    }

    @Test
    @DisplayName("큐 플러시: DB Batch Insert가 실패하더라도 예외를 먹고 스케줄러가 죽지 않아야 한다.")
    void flushToOutbox_FailsGracefully() {
        // Given
        PurchaseMessage msg = new PurchaseMessage("t1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP");
        promotionService.acceptPurchase(msg);

        doThrow(new RuntimeException("DB 터짐")).when(outboxBatchService).batchInsert(anyList());

        // When & Then
        // 💡 Exception이 밖으로 던져지면 @Scheduled 스레드가 죽으므로, 안 던져지는지(try-catch 방어) 검증
        assertDoesNotThrow(() -> promotionService.flushToOutbox());

        // 큐는 비워졌어야 함
        BlockingQueue<PurchaseTask> queue = (BlockingQueue<PurchaseTask>) ReflectionTestUtils.getField(promotionService, "memoryQueue");
        assertThat(queue).isEmpty();
    }

    @Test
    @DisplayName("팬딩 처리: 메모리 캐시에 이미 등록된 유저의 다른 TraceId 요청이 오면 DB 접근 없이 즉시 FAIL 처리한다.")
    void processPendingRequests_MemoryDuplicateBlock() {
        // Given
        ConcurrentMap<Long, String> cache = (ConcurrentMap<Long, String>) ReflectionTestUtils.getField(promotionService, "userCache");
        cache.put(1L, "trace-old");

        RequestOutbox duplicateOutbox = RequestOutbox.builder().traceId("trace-new").userId(1L).build();
        ReflectionTestUtils.setField(duplicateOutbox, "id", 100L);

        given(outboxRepository.findByStatus(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                .willReturn(List.of(duplicateOutbox));

        // When
        promotionService.processPendingRequests();

        // Then
        verify(outboxProcessor).markAsFailDirectly(100L);
        verify(outboxProcessor, never()).processSingleItem(anyLong());
    }
}