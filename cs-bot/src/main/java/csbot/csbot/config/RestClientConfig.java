package csbot.csbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder internalServiceClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    public RestClient linearClient(@Value("${linear.api-key}") String linearApiKey) {
        return RestClient.builder()
                .baseUrl("https://api.linear.app/graphql")
                .defaultHeader("Authorization", linearApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
