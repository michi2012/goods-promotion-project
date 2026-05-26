# 계획서: 결제 메트릭 에러 타입 레이블 분리

- 작성일: 2026-05-26

## 목표
`business_payment_error_total` 단일 카운터를 `type` 레이블로 분리하여 PG 시스템 장애(`pg_system_error`)와 비즈니스 거절(`pg_rejection`)을 Prometheus에서 독립적으로 추적한다.

## 성공 기준
- [ ] `business_payment_error_total{type="pg_system_error"}` 메트릭이 서킷브레이커 fallback 경로에서만 기록됨
- [ ] `business_payment_error_total{type="pg_rejection"}` 메트릭이 PG 정상 응답 후 거절 경로에서만 기록됨
- [ ] `.\gradlew.bat :serverC:compileJava` BUILD SUCCESSFUL
- [ ] alert-rules.yml의 PaymentBusinessErrorRateCritical/Warning이 `{type="pg_system_error"}`만 추적함

## 비범위 (Out of Scope)
- `pg_rejection` 세부 사유 분류 (잔액부족 / 카드한도 등) — PG API 연동 후 별도 작업
- PaymentService의 `paymentAttempts` 중복 카운트 문제 (Kafka 재시도 시 다중 증가) — 별도 작업
- 새 Gradle 의존성 추가 없음

## 단계별 작업 계획

### 단계 1: MockPgClient.java — fallback이 PgPaymentException을 throw하도록 변경
- 변경 파일: `serverC/src/main/java/weverse/serverC/service/MockPgClient.java`
- 변경 내용: `fallbackProcessPayments`가 `List<String>` 반환 대신 `PgPaymentException`을 throw. PaymentService가 예외를 catch해서 pg_system_error로 분류할 수 있도록 시그널 역할을 한다.
- 검증 방법: `.\gradlew.bat :serverC:compileJava`
- 롤백 방법: `git checkout serverC/src/main/java/weverse/serverC/service/MockPgClient.java`
- 예상 소요: 짧음

### 단계 2: PaymentService.java — 단일 paymentError 카운터를 타입 레이블 방식으로 교체
- 변경 파일: `serverC/src/main/java/weverse/serverC/service/PaymentService.java`
- 변경 내용:
  1. `paymentError` Counter 필드 제거
  2. `processPayments()` 호출을 try-catch로 감쌈
  3. catch(PgPaymentException): `type=pg_system_error` 기록 → FAILED 상태 저장 → outbox 저장 → 예외 삼킴(re-throw 금지, 트랜잭션 커밋 보장)
  4. 정상 반환 후 실패 ID 포함 시: `type=pg_rejection` 기록
- 검증 방법: `.\gradlew.bat :serverC:compileJava`
- 롤백 방법: `git checkout serverC/src/main/java/weverse/serverC/service/PaymentService.java`
- 예상 소요: 보통

### 단계 3: alert-rules.yml — PaymentBusiness 룰에 type 필터 추가
- 변경 파일: `monitoring/prometheus/alert-rules.yml`
- 변경 내용: `PaymentBusinessErrorRateCritical`, `PaymentBusinessErrorRateWarning` 두 룰의 expr에 `{type="pg_system_error"}` 레이블 셀렉터 추가
- 검증 방법: 파일 내용 직접 확인
- 롤백 방법: `git checkout monitoring/prometheus/alert-rules.yml`
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: fallback이 예외를 throw하면 Resilience4j가 이를 추가 실패로 카운트해 서킷브레이커 상태에 영향을 줄 수 있음 → 대응: MockPgClient는 Mock이므로 허용. 실제 PgClient 구현 시 별도 검토 필요.
- 리스크: catch 블록에서 DB 업데이트 중 예외 발생 시 메트릭은 기록됐으나 상태가 FAILED로 저장 안 될 수 있음 → 대응: 이는 기존 코드의 트랜잭션 범위 문제로, 이번 범위 밖.

## 의존성
- `PgPaymentException` 클래스 기존 존재 (`serverC/src/main/java/weverse/serverC/exception/PgPaymentException.java`)
- Micrometer MeterRegistry는 PaymentService에 이미 주입되어 있음
