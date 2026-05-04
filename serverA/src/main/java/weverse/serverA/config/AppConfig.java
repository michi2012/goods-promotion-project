package weverse.serverA.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableAsync      // EventNotifier의 @Async 작동을 위해 필수
@EnableScheduling // 스케줄러 작동을 위해 필수
public class AppConfig {
}