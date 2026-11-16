package promotion.serverC.config;

import com.fasterxml.jackson.core.JsonParseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    @DisplayName("메인 에러 핸들러: 실패 레코드는 원본 파티션 번호와 무관하게 파티션을 지정하지 않고 {원본}.DLT로 발행한다 (BL-037)")
    @SuppressWarnings("unchecked")
    void deadLetterIsPublishedWithoutFixedPartition() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        ProducerFactory<String, String> producerFactory = mock(ProducerFactory.class);
        lenient().when(template.getProducerFactory()).thenReturn(producerFactory);
        lenient().when(producerFactory.getConfigurationProperties()).thenReturn(Map.of());
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DefaultErrorHandler mainHandler = new KafkaConsumerConfig(template).errorHandler();
        ConsumerRecord<String, String> paymentCancel = new ConsumerRecord<>("payment-cancel", 2, 10L, "order-1", "{bad");

        // JSON 오류는 재시도하지 않으므로 바로 DLT로 간다
        mainHandler.handleRemaining(new JsonParseException(null, "bad json"), List.of(paymentCancel), consumer, container);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("payment-cancel.DLT");
        assertThat(sent.getValue().partition()).as("파티션 미지정 — 프로듀서가 고른다").isNull();
    }
}
