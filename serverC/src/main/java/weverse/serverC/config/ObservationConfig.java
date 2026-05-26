package weverse.serverC.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration
public class ObservationConfig {

    @Bean
    ObservationPredicate ignoreUnnecessaryObservations() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                String uri = serverContext.getCarrier().getRequestURI();

                return !uri.startsWith("/actuator")
                        && !uri.startsWith("/swagger-ui")
                        && !uri.startsWith("/v3/api-docs");
            }
            return true;
        };
    }
}
