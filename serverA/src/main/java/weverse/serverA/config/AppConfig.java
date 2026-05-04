package weverse.serverA.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync      // EventNotifier의 @Async 작동을 위해 필수
public class AppConfig {
}