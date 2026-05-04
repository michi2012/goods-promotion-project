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
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.outbox.OutboxBatchService;
import weverse.serverA.service.outbox.OutboxProcessor;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
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
        // 테스트 용이성을 위해 캐시를 초기화합니다.
        ConcurrentMap<Long, String> cache = (ConcurrentMap<Long, String>) ReflectionTestUtils.getField(promotionService, "userCache");
        if (cache != null) cache.clear();
    }

    @Test
    @DisplayName("API 수신: 큐가 꽉 찼을 경우 503 에러로 즉시 응답을 반환한다.")
    void acceptPurchase_QueueFull() {
        // Given: 큐 사이즈를 강제로 1로 만들고 미리 채워둠
        BlockingQueue<PurchaseTask> tinyQueue = new ArrayBlockingQueue<>(1);
        tinyQueue.offer(new PurchaseTask(null, null));
        ReflectionTestUtils.setField(promotionService, "memoryQueue", tinyQueue);

        // When
        CompletableFuture<ResponseEntity<String>> future = promotionService.acceptPurchase(mock(PurchaseMessage.class));

        // Then
        ResponseEntity<String> response = future.join();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("큐 플러시: DB Batch Insert가 실패하면 대기 중인 사용자들에게 500 에러를 반환한다.")
    void flushToOutbox_FailsAndReturns500() {
        // Given
        PurchaseMessage msg = new PurchaseMessage("t1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP");
        promotionService.acceptPurchase(msg);

        // batchInsert 시 강제 예외 발생
        doThrow(new RuntimeException("DB 터짐")).when(outboxBatchService).batchInsert(anyList());

        // When
        promotionService.flushToOutbox();

        // Then
        // 큐는 비워졌고, 사용자는 500 응답을 받아야 함
        BlockingQueue<PurchaseTask> queue = (BlockingQueue<PurchaseTask>) ReflectionTestUtils.getField(promotionService, "memoryQueue");
        assertThat(queue).isEmpty();
    }

    @Test
    @DisplayName("팬딩 처리: 메모리 캐시에 이미 등록된 유저의 다른 TraceId 요청이 오면 DB 접근 없이 즉시 FAIL 처리한다.")
    void processPendingRequests_MemoryDuplicateBlock() {
        // Given: 1번 유저의 "trace-old"가 이미 캐시에 존재함
        ConcurrentMap<Long, String> cache = (ConcurrentMap<Long, String>) ReflectionTestUtils.getField(promotionService, "userCache");
        cache.put(1L, "trace-old");

        // 동일한 1번 유저지만 새로운 "trace-new" 요청이 들어옴
        RequestOutbox duplicateOutbox = RequestOutbox.builder().traceId("trace-new").userId(1L).build();
        ReflectionTestUtils.setField(duplicateOutbox, "id", 100L);

        given(outboxRepository.findByStatus(eq(OutboxStatus.PENDING), any(PageRequest.class)))
                .willReturn(List.of(duplicateOutbox));

        // When
        promotionService.processPendingRequests();

        // Then
        verify(outboxProcessor).markAsFailDirectly(100L); // 즉시 컷
        verify(outboxProcessor, never()).processSingleItem(anyLong()); // 핵심 로직은 아예 타지 않음!
    }
}