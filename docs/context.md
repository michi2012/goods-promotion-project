# PR 맥락 노트: user-service 통합 + Gateway JWT 검증

---

## [완료] user-service 이식 통합

### 주요 결정
- **JWT 검증 위치**: Gateway에서 검증 후 X-User-Id 주입. user-service는 검증 안 함 (사용자 지시).
- **Refresh 쿠키 secure=false**: ALB TLS termination → HTTP 내부 전달 구조이므로 올바름 (사용자 확인).
- **ddl-auto: validate + Flyway**: create는 재시작 시 데이터 유실 위험. Flyway 의존성이 이미 있으므로 제대로 사용.
- **mysql-user 신규 컨테이너(포트 3309)**: DB-per-service 원칙. 기존 mysql(3307/order), mysql-c(3308/payment)와 동일 패턴.

### 채택하지 않은 대안
- **기존 mysql에 user 스키마 추가**: DB-per-service 원칙 위반
- **ddl-auto: update**: 운영 환경에서 예측 불가한 스키마 변경 위험
- **user-service에 JWTFilter 추가**: Gateway 중앙집중 검증과 중복

---

## [진행 중] Gateway JWT 검증 필터

### 주요 결정
- **GlobalFilter 방식**: 새 라우트 추가 시 필터 누락 위험 없음. 화이트리스트로 공개 경로만 예외 처리.
- **X-User-Id / X-User-Role 헤더 전달**: downstream에서 토큰 재파싱 불필요. 분산 추적에도 유용.
- **E2E 전 커밋 금지**: docker compose 우회 수단이 있으므로 검증 후 커밋 (CLAUDE.md 기준).

### 채택하지 않은 대안
- **GatewayFilterFactory (라우트별 적용)**: 새 라우트 추가 시 필터 누락 위험
- **Spring Security 추가**: 의존성 충돌 위험, GlobalFilter로 충분

### 관련 파일
- `gateway-service/src/main/java/.../filter/JwtAuthFilter.java` — GlobalFilter 구현 (신규)
- `user-service/src/main/java/.../config/JWTUtil.java` — claim 구조 참조용
