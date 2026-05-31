package promotion.serverC.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    @InjectMocks
    private OutboxEventService outboxEventService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("페이로드가 정상적으로 직렬화되어 저장된다.")
    void save_success() throws Exception {
        // given
        String aggregateId = "user-123";
        String topic = "test-topic";
        Object payload = new Object(); // 테스트용 객체
        String expectedJson = "{\"key\":\"value\"}";

        given(objectMapper.writeValueAsString(payload)).willReturn(expectedJson);

        // when
        outboxEventService.save(aggregateId, topic, payload);

        // then
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent savedEvent = captor.getValue();
        assertThat(savedEvent.getAggregateId()).isEqualTo(aggregateId);
        assertThat(savedEvent.getTopic()).isEqualTo(topic);
        assertThat(savedEvent.getPayload()).isEqualTo(expectedJson);
    }

    @Test
    @DisplayName("직렬화 실패 시 IllegalStateException 예외가 발생한다.")
    void save_fail_when_json_processing_exception() throws Exception {
        // given
        given(objectMapper.writeValueAsString(any())).willThrow(JsonProcessingException.class);

        // when & then
        assertThatThrownBy(() -> outboxEventService.save("id", "topic", new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Outbox 직렬화 실패");
    }
}