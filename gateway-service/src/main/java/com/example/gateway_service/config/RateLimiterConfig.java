package com.example.gateway_service.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            // ALB는 클라이언트 실제 IP를 X-Forwarded-For에 삽입
            // getRemoteAddress()는 ALB 내부 IP를 반환하므로 헤더 우선
            String forwarded = exchange.getRequest()
                .getHeaders()
                .getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return Mono.just(forwarded.split(",")[0].trim());
            }
            var remoteAddress = exchange.getRequest().getRemoteAddress();
            if (remoteAddress != null) {
                return Mono.just(remoteAddress.getAddress().getHostAddress());
            }
            return Mono.just("unknown");
        };
    }
}
