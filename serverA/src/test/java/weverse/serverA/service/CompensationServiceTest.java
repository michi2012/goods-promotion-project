package weverse.serverA.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import weverse.serverA.dto.CompensationRequest;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.dlt.DeadLetterService;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CompensationServiceTest {

    @InjectMocks
    private CompensationService compensationService;

    @Mock private GoodsRepository goodsRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private DeadLetterService deadLetterService;

    @Test
    @DisplayName("보상 성공: 아웃박스 상태 변경 후 재고를 성공적으로 복구한다.")
    void compensate_Success() {
        // Given
        CompensationRequest request = new CompensationRequest("trace-1", 1L, 2, "잔액 부족");
        given(outboxRepository.markAsCompensatedAtomically("trace-1")).willReturn(1);
        given(goodsRepository.increaseStockAtomically(1L, 2)).willReturn(1);

        // When
        compensationService.compensate(List.of(request));

        // Then
        verify(outboxRepository).markAsCompensatedAtomically("trace-1");
        verify(goodsRepository).increaseStockAtomically(1L, 2);
        verify(deadLetterService, never()).saveDeadLetter(anyString(), anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("멱등성 방어: 이미 보상 처리된 TraceId라면 재고를 복구하지 않고 스킵한다.")
    void compensate_SkipWhenAlreadyCompensated() {
        // Given
        CompensationRequest request = new CompensationRequest("trace-1", 1L, 2, "잔액 부족");
        // 이미 처리되어서 업데이트된 행이 0개라고 가정
        given(outboxRepository.markAsCompensatedAtomically("trace-1")).willReturn(0);

        // When
        compensationService.compensate(List.of(request));

        // Then
        verify(goodsRepository, never()).increaseStockAtomically(anyLong(), anyInt()); // 재고 증가 호출 안 됨!
        verify(deadLetterService, never()).saveDeadLetter(anyString(), anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("보상 실패: 재고를 찾을 수 없거나 DB 에러 시 DLT로 이관한다.")
    void compensate_FailsAndMovesToDlt() {
        // Given
        CompensationRequest request = new CompensationRequest("trace-1", 1L, 2, "잔액 부족");
        given(outboxRepository.markAsCompensatedAtomically("trace-1")).willReturn(1);
        given(goodsRepository.increaseStockAtomically(1L, 2)).willReturn(0); // 재고 복구 실패 (예외 발생 조건)

        // When
        compensationService.compensate(List.of(request));

        // Then
        // DLT 이관 메서드가 호출되었는지 검증
        verify(deadLetterService).saveDeadLetter(eq("trace-1"), eq(1L), eq(2), anyString());
    }
}