package weverse.serverA.service.dlt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import weverse.serverA.entity.DeadLetter;
import weverse.serverA.entity.DltStatus;
import weverse.serverA.exception.AlreadyResolvedDltException;
import weverse.serverA.repository.DeadLetterRepository;
import weverse.serverA.repository.GoodsRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {

    @InjectMocks private DeadLetterService deadLetterService;
    @Mock private DeadLetterRepository deadLetterRepository;
    @Mock private GoodsRepository goodsRepository;

    @Test
    @DisplayName("DLT 저장: 실패한 요청 정보를 DeadLetter 테이블에 저장한다.")
    void saveDeadLetter() {
        deadLetterService.saveDeadLetter("trace-1", 1L, 2, "이유");
        verify(deadLetterRepository).save(any(DeadLetter.class));
    }

    @Test
    @DisplayName("어드민 복구 성공: DLT 상태를 확인하고 재고를 복구한다.")
    void retryDeadLetter_Success() {
        DeadLetter dlt = DeadLetter.builder().traceId("trace-1").goodsId(1L).quantity(2).build();
        ReflectionTestUtils.setField(dlt, "status", DltStatus.UNRESOLVED);

        given(deadLetterRepository.findById(10L)).willReturn(Optional.of(dlt));
        given(goodsRepository.increaseStockAtomically(1L, 2)).willReturn(1);

        deadLetterService.retryDeadLetter(10L);

        assertThat(dlt.getStatus()).isEqualTo(DltStatus.RESOLVED);
    }

    @Test
    @DisplayName("어드민 복구 실패: 이미 해결(RESOLVED)된 DLT라면 예외를 던진다.")
    void retryDeadLetter_ThrowsWhenAlreadyResolved() {
        DeadLetter dlt = DeadLetter.builder().build();
        ReflectionTestUtils.setField(dlt, "status", DltStatus.RESOLVED);
        given(deadLetterRepository.findById(10L)).willReturn(Optional.of(dlt));

        assertThatThrownBy(() -> deadLetterService.retryDeadLetter(10L))
                .isInstanceOf(AlreadyResolvedDltException.class);
    }
}
