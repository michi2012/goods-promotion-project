package weverse.serverC.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // 1. order-completed 토픽과 DLT
    @Bean
    public NewTopic orderCompletedTopic() {
        return TopicBuilder.name("order-completed")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic orderCompletedDltTopic() {
        return TopicBuilder.name("order-completed.DLT")
                           .partitions(1)
                           .replicas(1)
                           .build();
    }

    // 2. payment-request 토픽과 DLT
    @Bean
    public NewTopic paymentRequestTopic() {
        return TopicBuilder.name("payment-request")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic paymentRequestDltTopic() {
        return TopicBuilder.name("payment-request.DLT")
                           .partitions(1)
                           .replicas(1)
                           .build();
    }

    // 3. 서버 C가 발행하는 결과 토픽
    @Bean
    public NewTopic paymentResultTopic() {
        return TopicBuilder.name("payment-result")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }
}