package weverse.serverA.service.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import weverse.serverA.dto.SoldOutEvent;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.exception.DuplicateOrderException;
import weverse.serverA.exception.SoldOutException;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.EventNotifier;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    @InjectMocks
    private OutboxProcessor outboxProcessor;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private GoodsRepository goodsRepository;

    @Mock
    private EventNotifier eventNotifier;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("성공: 재고가 충분하고 중복 결제가 아닐 경우 성공(SUCCESS) 처리된다.")
    void processSingleItem_Success() {
        // Given
        RequestOutbox outbox = RequestOutbox.builder()
                                            .userId(100L).goodsId(1L).quantity(1).status(OutboxStatus.PENDING).build();

        given(outboxRepository.findById(anyLong())).willReturn(Optional.of(outbox));
        // 중복 아님
        given(outboxRepository.existsByUserIdAndStatusIn(eq(100L), any())).willReturn(false);
        // 원자적 차감 성공 (1 row updated)
        given(goodsRepository.decreaseStockAtomically(1L, 1)).willReturn(1);
        // 남은 재고 10개 (품절 아님)
        given(goodsRepository.findStockById(1L)).willReturn(10);

        // When
        outboxProcessor.processSingleItem(1L);

        // Then
        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SUCCESS);
    }

    @Test
    @DisplayName("실패: 이미 성공/진행 중인 결제 내역이 있으면 DuplicateOrderException이 발생하고 FAIL 처리된다.")
    void processSingleItem_DuplicateOrder() {
        // Given
        RequestOutbox outbox = RequestOutbox.builder()
                                            .userId(100L).goodsId(1L).status(OutboxStatus.PENDING).build();

        given(outboxRepository.findById(anyLong())).willReturn(Optional.of(outbox));
        // 이미 구매한 이력이 있음 (true)
        given(outboxRepository.existsByUserIdAndStatusIn(eq(100L), any())).willReturn(true);

        // When & Then
        assertThatThrownBy(() -> outboxProcessor.processSingleItem(1L))
                .isInstanceOf(DuplicateOrderException.class);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAIL);
    }

    @Test
    @DisplayName("실패: DB 원자적 차감 쿼리 결과가 0이면 SoldOutException이 발생하고 서버 B로 품절 알림을 보낸다.")
    void processSingleItem_SoldOut() {
        // Given
        RequestOutbox outbox = RequestOutbox.builder()
                                            .userId(100L).goodsId(1L).quantity(1).status(OutboxStatus.PENDING).build();

        given(outboxRepository.findById(anyLong())).willReturn(Optional.of(outbox));
        given(outboxRepository.existsByUserIdAndStatusIn(eq(100L), any())).willReturn(false);
        // 재고 부족으로 업데이트된 행이 0개
        given(goodsRepository.decreaseStockAtomically(1L, 1)).willReturn(0);

        // When & Then
        assertThatThrownBy(() -> outboxProcessor.processSingleItem(1L))
                .isInstanceOf(SoldOutException.class);

        assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAIL);
        verify(eventPublisher).publishEvent(any(SoldOutEvent.class)); // 품절 이벤트 발행 검증
    }
}