package promotion.serverA.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // 1. purchase_events 토픽과 DLT
    @Bean
    public NewTopic purchaseEventsTopic() {
        return TopicBuilder.name("purchase_events")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic purchaseEventsDltTopic() {
        return TopicBuilder.name("purchase_events.DLT")
                           .partitions(1)
                           .replicas(1)
                           .build();
    }

    // 2. status-update-result 토픽과 DLT
    @Bean
    public NewTopic statusUpdateResultTopic() {
        return TopicBuilder.name("status-update-result")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic statusUpdateResultDltTopic() {
        return TopicBuilder.name("status-update-result.DLT")
                           .partitions(1)
                           .replicas(1)
                           .build();
    }

    // 3. payment-result 토픽과 DLT
    @Bean
    public NewTopic paymentResultTopic() {
        return TopicBuilder.name("payment-result")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic paymentResultDltTopic() {
        return TopicBuilder.name("payment-result.DLT")
                           .partitions(1)
                           .replicas(1)
                           .build();
    }

    // 4. Server A가 발행하는 추가 토픽들
    @Bean
    public NewTopic paymentRequestTopic() {
        return TopicBuilder.name("payment-request")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic orderStatusUpdateTopic() {
        return TopicBuilder.name("order-status-update")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic paymentCancelTopic() {
        return TopicBuilder.name("payment-cancel")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }
}