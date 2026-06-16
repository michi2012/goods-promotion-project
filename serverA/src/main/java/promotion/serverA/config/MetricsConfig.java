package promotion.serverA.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import promotion.serverA.entity.DltStatus;
import promotion.serverA.repository.DeadLetterRepository;

import java.util.List;

@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    @Bean
    public Gauge deadLetterUnresolvedGauge(MeterRegistry registry, DeadLetterRepository deadLetterRepository) {
        return Gauge.builder("business_dead_letter_unresolved_total", deadLetterRepository,
                        repo -> (double) repo.countByStatus(DltStatus.UNRESOLVED))
                .description("현재 미해결(UNRESOLVED) DLT 레코드 수")
                .register(registry);
    }

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags(
            @Value("${spring.application.name:unknown}") String applicationName) {

        return registry -> registry.config()
                                   .commonTags(List.of(
                                           io.micrometer.core.instrument.Tag.of("application", applicationName)
                                   ));
    }
}