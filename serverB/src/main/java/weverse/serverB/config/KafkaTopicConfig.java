package weverse.serverB.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // 1. order-status-update 토픽과 DLT
    @Bean
    public NewTopic orderStatusUpdateTopic() {
        return TopicBuilder.name("order-status-update")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic orderStatusUpdateDltTopic() {
        return TopicBuilder.name("order-status-update.DLT")
                           .partitions(1)
                           .replicas(1)
                           .build();
    }

    // 2. stock-snapshot 토픽과 DLT
    @Bean
    public NewTopic stockSnapshotTopic() {
        return TopicBuilder.name("stock-snapshot")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }

    @Bean
    public NewTopic stockSnapshotDltTopic() {
        return TopicBuilder.name("stock-snapshot.DLT")
                           .partitions(1)
                           .replicas(1)
                           .build();
    }

    // 3. 서버 B가 발행하는 결과 토픽
    @Bean
    public NewTopic statusUpdateResultTopic() {
        return TopicBuilder.name("status-update-result")
                           .partitions(3)
                           .replicas(1)
                           .build();
    }
}