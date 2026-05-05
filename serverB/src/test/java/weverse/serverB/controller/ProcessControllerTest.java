package weverse.serverB.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import weverse.serverB.dto.PurchaseMessage;
import weverse.serverB.exception.SystemOverloadException;
import weverse.serverB.service.PipelineService;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProcessControllerTest {

    @InjectMocks
    private ProcessController processController;

    @Mock
    private PipelineService pipelineService;

    @Test
    @DisplayName("성공: 허용량 이내의 벌크 요청은 정상적으로 접수된다.")
    void receiveBulk_Success() {
        // Given
        List<PurchaseMessage> messages = List.of(new PurchaseMessage("trace1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP"));

        // When
        ResponseEntity<?> response = processController.receiveBulk(messages);

        // Then
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        verify(pipelineService).processBulkData(messages);
    }

    @Test
    @DisplayName("방어: 최대 인플라이트(Max In-Flight)를 초과하는 요청이 들어오면 예외를 발생시킨다.")
    void receiveBulk_ThrowsSystemOverloadException() {
        // Given
        // 리플렉션을 사용하여 현재 진행 중인 요청 수를 최대치(20000)에 가깝게 조작
        AtomicInteger inFlight = (AtomicInteger) ReflectionTestUtils.getField(processController, "currentInFlightRequests");
        inFlight.set(19999);

        // 2개의 요청을 추가로 보내면 20001이 되어 제한(20000)을 초과함
        List<PurchaseMessage> messages = List.of(
                new PurchaseMessage("t1", 1L, 1L, 1, "C", "A", "1", "0", "E", "M", "I"),
                new PurchaseMessage("t2", 2L, 1L, 1, "C", "A", "1", "0", "E", "M", "I")
        );

        // When & Then
        assertThatThrownBy(() -> processController.receiveBulk(messages))
                .isInstanceOf(SystemOverloadException.class);

        // 예외 발생 후 in-flight 카운트가 정상적으로 롤백되었는지 검증 (19999 + 2 - 2 = 19999)
        assertThat(inFlight.get()).isEqualTo(19999);
    }
}