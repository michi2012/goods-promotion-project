package com.example.user_service.controller;

import com.example.user_service.config.securiy.jwt.JWTUtil;
import com.example.user_service.entity.RefreshToken;
import com.example.user_service.repository.RefreshTokenRepository;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;

@RestController
@RequiredArgsConstructor
public class ReissueController {

    private final JWTUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;

    @PostMapping("/reissue")
    public ResponseEntity<String> reissue(HttpServletRequest request, HttpServletResponse response) {

        // 1. 쿠키에서 Refresh Token 추출
        String refresh = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("refresh")) {
                    refresh = cookie.getValue();
                }
            }
        }

        if (refresh == null) {
            return new ResponseEntity<>("refresh token null", HttpStatus.BAD_REQUEST);
        }

        // 2. 검증 (만료, 카테고리, DB 존재 여부)
        try {
            jwtUtil.isExpired(refresh);
        } catch (ExpiredJwtException e) {
            return new ResponseEntity<>("refresh token expired", HttpStatus.BAD_REQUEST);
        }

        String category = jwtUtil.getCategory(refresh);
        if (!category.equals("refresh")) {
            return new ResponseEntity<>("invalid refresh token", HttpStatus.BAD_REQUEST);
        }

        if (!refreshTokenRepository.existsByRefresh(refresh)) {
            return new ResponseEntity<>("invalid refresh token", HttpStatus.BAD_REQUEST);
        }

        // 3. 새로운 토큰 생성
        String userId = jwtUtil.getUserId(refresh);
        String role = jwtUtil.getRole(refresh);

        String newAccess = jwtUtil.createJwt("access", userId, role, 600000L);
        String newRefresh = jwtUtil.createJwt("refresh", userId, role, 86400000L);

        // 4. DB 업데이트 (기존 삭제 -> 새거 저장)
        refreshTokenRepository.deleteByRefresh(refresh);
        addRefreshEntity(userId, newRefresh, 86400000L);

        // 5. 응답 설정
        response.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess);

        // 재발급된 토큰도 SameSite 적용
        ResponseCookie cookie = ResponseCookie.from("refresh", newRefresh)
                                              .path("/")
                                              .sameSite("Strict")
                                              .httpOnly(true)
                                              .secure(false)
                                              .maxAge(24 * 60 * 60)
                                              .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        return new ResponseEntity<>(HttpStatus.OK);
    }

    private void addRefreshEntity(String userId, String refresh, Long expiredMs) {
        Date date = new Date(System.currentTimeMillis() + expiredMs);
        RefreshToken refreshToken = new RefreshToken(userId, refresh, date.toString());
        refreshTokenRepository.save(refreshToken);
    }
}