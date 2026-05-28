package weverse.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import weverse.serverB.dto.OrderStatusMessage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderStatusEventHandlerTest {

    @Mock
    private OrderQueryService orderQueryService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderStatusEventHandler orderStatusEventHandler;

    @Test
    @DisplayName("주문 상태가 PENDING이면 레디스 업데이트 후 성공 결과를 동기 발행한다")
    void handleStatusUpdate_Pending_Success() throws Exception {
        // given
        OrderStatusMessage msg = new OrderStatusMessage("trace-123", 1L, "PENDING");
        given(kafkaTemplate.send(eq("status-update-result"), eq("trace-123"), contains("\"success\":true")))
                .willReturn(CompletableFuture.completedFuture(null));

        // when
        orderStatusEventHandler.handleStatusUpdate(msg);

        // then
        verify(orderQueryService).updateOrderStatus("trace-123", "PENDING");
        verify(kafkaTemplate).send(eq("status-update-result"), eq("trace-123"), contains("\"success\":true"));
    }

    @Test
    @DisplayName("주문 상태가 PENDING이 아니면 레디스 업데이트만 진행하고 결과를 발행하지 않는다")
    void handleStatusUpdate_NotPending_Success() throws Exception {
        // given
        OrderStatusMessage msg = new OrderStatusMessage("trace-123", 1L, "PAID");

        // when
        orderStatusEventHandler.handleStatusUpdate(msg);

        // then
        verify(orderQueryService).updateOrderStatus("trace-123", "PAID");
        // 카프카 발행 로직이 아예 호출되지 않았는지 검증
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("레디스 업데이트 중 예외가 발생하면 PENDING 상태일 때 실패 결과를 발행한다")
    void handleStatusUpdate_Pending_Exception() throws Exception {
        // given
        OrderStatusMessage msg = new OrderStatusMessage("trace-123", 1L, "PENDING");
        doThrow(new RuntimeException("Redis connection error"))
                .when(orderQueryService).updateOrderStatus(anyString(), anyString());

        // when
        orderStatusEventHandler.handleStatusUpdate(msg);

        // then — Redis 실패는 fire-and-forget 실패 알림 후 정상 종료
        verify(kafkaTemplate).send(eq("status-update-result"), eq("trace-123"), contains("\"success\":false"));
    }

    @Test
    @DisplayName("Kafka 발행이 타임아웃되면 예외가 전파되어 consumer ErrorHandler가 재시도할 수 있다")
    void handleStatusUpdate_Pending_KafkaTimeout() throws Exception {
        // given
        OrderStatusMessage msg = new OrderStatusMessage("trace-123", 1L, "PENDING");
        CompletableFuture<Object> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("Kafka timeout"));
        given(kafkaTemplate.send(eq("status-update-result"), eq("trace-123"), contains("\"success\":true")))
                .willReturn((CompletableFuture) failedFuture);

        // when & then — 예외가 메서드 밖으로 전파됨
        assertThatThrownBy(() -> orderStatusEventHandler.handleStatusUpdate(msg))
                .isInstanceOf(Exception.class);
    }
}