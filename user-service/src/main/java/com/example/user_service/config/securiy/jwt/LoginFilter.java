package com.example.user_service.config.securiy.jwt;

import com.example.user_service.config.securiy.CustomUserDetails;
import com.example.user_service.entity.RefreshToken;
import com.example.user_service.repository.RefreshTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Collection;
import java.util.Date;
import java.util.Iterator;

public class LoginFilter extends UsernamePasswordAuthenticationFilter {

    private final AuthenticationManager authenticationManager;
    private final JWTUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;

    public LoginFilter(AuthenticationManager authenticationManager, JWTUtil jwtUtil, RefreshTokenRepository refreshTokenRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        String username = obtainUsername(request);
        String password = obtainPassword(request);
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(username, password, null);
        return authenticationManager.authenticate(authToken);
    }

    @Override
    protected void successfulAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Authentication authentication) {
        CustomUserDetails customUserDetails = (CustomUserDetails) authentication.getPrincipal();
        String userId = customUserDetails.getUserId();

        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        Iterator<? extends GrantedAuthority> iterator = authorities.iterator();
        GrantedAuthority auth = iterator.next();
        String role = auth.getAuthority();

        // 1. 토큰 생성
        String access = jwtUtil.createJwt("access", userId, role, 600000L); // 10분
        String refresh = jwtUtil.createJwt("refresh", userId, role, 86400000L); // 24시간

        // 2. Refresh 토큰 DB 저장
        addRefreshEntity(userId, refresh, 86400000L);

        // 3. 응답 설정
        response.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + access);

        // SameSite 설정이 적용된 쿠키 생성
        ResponseCookie cookie = createRefreshCookie(refresh);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        response.setStatus(HttpStatus.OK.value());
    }

    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request, HttpServletResponse response, AuthenticationException failed) {
        response.setStatus(401);
    }

    private void addRefreshEntity(String username, String refresh, Long expiredMs) {
        Date date = new Date(System.currentTimeMillis() + expiredMs);
        RefreshToken refreshToken = new RefreshToken(username, refresh, date.toString());
        refreshTokenRepository.save(refreshToken);
    }

    private ResponseCookie createRefreshCookie(String refresh) {
        return ResponseCookie.from("refresh", refresh)
                             .path("/")
                             .sameSite("Strict") // CSRF 방어
                             .httpOnly(true)     // XSS 방어
                             .secure(false)      // HTTPS 적용 시 true
                             .maxAge(24 * 60 * 60)
                             .build();
    }
}