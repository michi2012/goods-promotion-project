package promotion.serverA.config;

import com.fasterxml.jackson.core.JsonParseException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaConsumerConfigTest {

    @Test
    @DisplayName("실패 레코드는 원본 파티션 번호와 무관하게 파티션을 지정하지 않고 {원본}.DLT로 발행한다 (DLT는 파티션 1개 — BL-037)")
    @SuppressWarnings("unchecked")
    void deadLetterIsPublishedWithoutFixedPartition() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        ProducerFactory<String, String> producerFactory = mock(ProducerFactory.class);
        lenient().when(template.getProducerFactory()).thenReturn(producerFactory);
        lenient().when(producerFactory.getConfigurationProperties()).thenReturn(Map.of());
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        DefaultErrorHandler handler = new KafkaConsumerConfig(template).errorHandler();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("payment-result", 2, 10L, "order-1", "{bad");

        // JSON 오류는 재시도하지 않으므로 바로 DLT로 간다
        handler.handleRemaining(new JsonParseException(null, "bad json"), List.of(record),
                mock(Consumer.class), mock(MessageListenerContainer.class));

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("payment-result.DLT");
        assertThat(sent.getValue().partition()).as("파티션 미지정 — 프로듀서가 고른다").isNull();
    }
}
