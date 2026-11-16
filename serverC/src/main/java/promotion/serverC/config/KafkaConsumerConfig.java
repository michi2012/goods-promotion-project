package promotion.serverC.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.web.client.ResourceAccessException;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Bean
    public DefaultErrorHandler errorHandler() {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) ->
                        new org.apache.kafka.common.TopicPartition(
                                record.topic() + ".DLT",
                                -1 // 음수면 프로듀서가 파티션을 고른다 — DLT는 파티션 1개라 원본 번호를 쓰면 원본 파티션 1·2가 발행 실패 (BL-037)
                        )
        );

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        errorHandler.setRetryListeners((record, ex, attempt) ->
                log.warn(
                        "Kafka 레코드 재시도 시도 #{} → topic={} / partition={} / offset={} / error={}",
                        attempt,
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        ex.getMessage()
                )
        );

        // 재시도할 오류: 네트워크, DB 일시적 오류
        errorHandler.addRetryableExceptions(ResourceAccessException.class, DataAccessException.class);

        // 재시도하지 않을 오류: 데이터/코드 문제
        errorHandler.addNotRetryableExceptions(
                JsonProcessingException.class,
                MessageConversionException.class,
                NullPointerException.class,
                DataIntegrityViolationException.class
        );
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }

    // =========================================================
    // 2. [DLT용] DB 기록 실패 시 -> 짧게 재시도 후 메트릭(P0)·로그를 남기고 오프셋을 넘김
    // =========================================================
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dltKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            MeterRegistry meterRegistry) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(dltErrorHandler(meterRegistry));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setObservationEnabled(true);

        return factory;
    }

    // DLT 핸들러(payment-cancel.DLT → payment_cancel_failures 기록)가 실패하면 일시적 DB 장애를 고려해
    // 지수 백오프로 5회(1→2→4→8→16초) 재시도한다. 그래도 실패하거나 파싱 불가면 그 취소 건이 자동 재시도에서
    // 빠지므로 메트릭을 올려 P0(PaymentCancelDltRecordFailed)를 울리고 원본을 로그로 남긴다.
    DefaultErrorHandler dltErrorHandler(MeterRegistry meterRegistry) {
        // 첫 실패 전에 0으로 등록해 둬야 Prometheus increase()가 첫 증가를 놓치지 않는다 (P0 PaymentCancelDltRecordFailed)
        Counter recordFailed = Counter.builder("business_payment_cancel_dlt_record_failed_total")
                .description("payment-cancel DLT 도착 건을 실패 기록(payment_cancel_failures)에 남기지 못한 건수 — 관리자 처리 필요")
                .register(meterRegistry);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(5);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(16000L);

        DefaultErrorHandler dltErrorHandler = new DefaultErrorHandler(
                (record, exception) -> {
                    recordFailed.increment();
                    // exception은 보통 ListenerExecutionFailedException으로 감싸져 있어 원인까지 스택트레이스로 남긴다
                    log.error("🚨 [DLT 처리 실패] 실패 기록 불가 — 관리자 처리 필요. topic={}, offset={}, payload={}",
                            record.topic(), record.offset(), record.value(), exception);
                },
                backOff
        );
        dltErrorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        dltErrorHandler.setAckAfterHandle(true);
        return dltErrorHandler;
    }
}