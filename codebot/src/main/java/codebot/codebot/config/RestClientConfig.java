package codebot.codebot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    private static RestClient buildWithTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Bean
    public RestClient lokiClient(@Value("${observability.loki.url}") String lokiUrl) {
        return buildWithTimeout(lokiUrl);
    }

    @Bean
    public RestClient tempoClient(@Value("${observability.tempo.url}") String tempoUrl) {
        return buildWithTimeout(tempoUrl);
    }

    @Bean
    public RestClient prometheusClient(@Value("${observability.prometheus.url}") String prometheusUrl) {
        return buildWithTimeout(prometheusUrl);
    }

    @Bean
    public RestClient linearClient(@Value("${linear.api-key}") String linearApiKey) {
        return RestClient.builder()
                .baseUrl("https://api.linear.app/graphql")
                .defaultHeader("Authorization", linearApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public RestClient githubClient(@Value("${github.token:}") String githubToken) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github.v3+json")
                .requestFactory(factory);
        if (!githubToken.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + githubToken);
        }
        return builder.build();
    }
}
