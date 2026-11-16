package promotion.serverC.config;

import com.fasterxml.jackson.core.JsonParseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class KafkaConsumerConfigTest {

    private static final String FAILED_METRIC = "business_payment_cancel_dlt_record_failed_total";

    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>("payment-cancel.DLT", 0, 0L, "order-1", "{\"orderId\":\"order-1\"}");

    @SuppressWarnings("unchecked")
    private final Consumer<String, String> consumer = mock(Consumer.class);
    // isRunning()=false(mock 기본값)면 백오프 대기가 짧게 끝난다
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    private SimpleMeterRegistry meterRegistry;
    private DefaultErrorHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        handler = new KafkaConsumerConfig(kafkaTemplate).dltErrorHandler(meterRegistry);
    }

    @Test
    @DisplayName("DLT 실패 메트릭은 첫 실패 전부터 0으로 등록돼 있다")
    void metricRegisteredAtZero() {
        assertThat(meterRegistry.find(FAILED_METRIC).counter()).isNotNull();
        assertThat(meterRegistry.counter(FAILED_METRIC).count()).isZero();
    }

    @Test
    @DisplayName("DB 기록 실패는 5회 재시도하고, 그래도 실패하면 메트릭을 올리고 오프셋을 넘긴다")
    void retriesThenRecordsFailure() {
        DataAccessResourceFailureException ex = new DataAccessResourceFailureException("DB 장애");

        // 재시도 중에는 RecordInRetryException(패키지 전용)으로 컨테이너에 "seek 후 재전달"을 알린다
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> handler.handleRemaining(ex, List.of(record), consumer, container))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("not yet recovered");
            assertThat(meterRegistry.counter(FAILED_METRIC).count()).isZero();
        }
        handler.handleRemaining(ex, List.of(record), consumer, container);

        assertThat(meterRegistry.counter(FAILED_METRIC).count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("JSON 파싱 오류는 재시도하지 않고 바로 메트릭을 올린다")
    void parseErrorNotRetried() {
        handler.handleRemaining(new JsonParseException(null, "bad json"), List.of(record), consumer, container);

        assertThat(meterRegistry.counter(FAILED_METRIC).count()).isEqualTo(1.0);
    }
}
