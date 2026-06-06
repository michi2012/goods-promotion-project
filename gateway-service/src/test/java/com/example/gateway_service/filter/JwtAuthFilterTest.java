package com.example.gateway_service.filter;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final String SECRET = "local-dev-jwt-secret-key-must-be-at-least-256-bits-for-hs256-algorithm";

    private JwtAuthFilter filter;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(SECRET);
        secretKey = new SecretKeySpec(
                SECRET.getBytes(StandardCharsets.UTF_8),
                Jwts.SIG.HS256.key().build().getAlgorithm()
        );
    }

    private String createToken(String category, String userId, String role, long expiryMs) {
        return Jwts.builder()
                   .claim("category", category)
                   .claim("userId", userId)
                   .claim("role", role)
                   .issuedAt(new Date())
                   .expiration(new Date(System.currentTimeMillis() + expiryMs))
                   .signWith(secretKey)
                   .compact();
    }

    @Test
    @DisplayName("POST /login은 토큰 없이 통과한다")
    void postLogin_passesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/login").build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /reissue는 토큰 없이 통과한다")
    void postReissue_passesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/reissue").build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
    }

    @Test
    @DisplayName("POST /api/users (회원가입)는 토큰 없이 통과한다")
    void postUsers_passesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users").build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(any());
    }

    @Test
    @DisplayName("Authorization 헤더 없으면 401 반환")
    void noAuthHeader_returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/1").build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("위변조 토큰이면 401 반환")
    void tamperedToken_returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/1")
                                     .header("Authorization", "Bearer invalid.token.value")
                                     .build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("만료된 토큰이면 401 반환")
    void expiredToken_returns401() {
        String expiredToken = createToken("access", "user-uuid", "ROLE_USER", -1000L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/1")
                                     .header("Authorization", "Bearer " + expiredToken)
                                     .build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("refresh 토큰으로 접근하면 401 반환")
    void refreshToken_returns401() {
        String refreshToken = createToken("refresh", "user-uuid", "ROLE_USER", 60000L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/1")
                                     .header("Authorization", "Bearer " + refreshToken)
                                     .build()
        );
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("유효한 access 토큰이면 통과하고 X-User-Id, X-User-Role 헤더가 추가된다")
    void validAccessToken_passesWithUserHeaders() {
        String token = createToken("access", "user-uuid-123", "ROLE_USER", 60000L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/1")
                                     .header("Authorization", "Bearer " + token)
                                     .build()
        );

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(captor.capture())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("user-uuid-123");
        assertThat(captured.getRequest().getHeaders().getFirst("X-User-Role")).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("클라이언트가 X-User-Id 헤더를 직접 설정해도 필터가 덮어쓴다")
    void spoofedUserIdHeader_isOverwritten() {
        String token = createToken("access", "real-uuid", "ROLE_USER", 60000L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/1")
                                     .header("Authorization", "Bearer " + token)
                                     .header("X-User-Id", "spoofed-uuid")
                                     .build()
        );

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        when(chain.filter(captor.capture())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo("real-uuid");
    }
}
