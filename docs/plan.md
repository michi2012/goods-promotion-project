# 계획서: 사용자 환불 + 환불 실패 재처리 (BL-029)

- 작성일: 2026-11-14
- 관련 이슈/티켓: BL-029 / 명세 `docs/specs/user-refund.md` (요구사항·설계 확인 ☑)

BL-029는 PR 3개로 나눈다. 각 PR은 착수할 때 이 문서에 섹션을 추가한다.
1. **PR① serverC** — 멱등 취소, `payment-cancelled` 발행, 실패 기록·자동 재시도·관리자 API, 알람 ← 이 섹션
2. PR② serverA + 게이트웨이 + serverB 계약 — 환불 API, `paid_at`, 환불 완료 처리·재고 복구
3. PR③ cs-bot + FAQ + 프론트

### BL-029 마지막 PR에서 할 일
- 아키텍처 문서 갱신: `docs/architecture/arch-snapshot.md`(토픽 맵·고객 환불 흐름·주요 엔티티·스케줄러, 140·302행의 `refund_fatal` "수동 정산" 설명), `docs/architecture/flows/purchase-saga-flow.md` 122행, `docs/design-notes.md` 179행 — `refund_fatal`은 "자동 재시도 진입", 관리자 처리 필요는 `business_payment_cancel_retry_exhausted_total`

## [진행 중] PR① serverC — PG 취소 멱등화 + 실패 재처리

### 목표
serverC의 PG 취소를 orderId 단위 멱등으로 바꾸고, 취소 성공 시 `payment-cancelled`를 발행한다. 취소가 실패하면 기록 → 자동 재시도 → 소진 시 알람 → 관리자 재처리까지 되게 한다. 이번 PR에서는 Saga 망취소 실패에 적용되고, PR②의 사용자 환불이 같은 경로를 쓴다.

### 성공 기준
- [ ] PAID 결제에 `payment-cancel`이 오면 PG 취소 1회 → `payments.status=CANCELLED` + `payment-cancelled` Outbox 저장이 같은 트랜잭션에서 일어난다. 이미 CANCELLED면 PG 호출 없이 `payment-cancelled`만 다시 저장한다 (단위·통합 테스트)
- [ ] PG에서 이미 취소된 거래를 다시 취소하면 성공으로 처리된다 (MockPgClient·서비스 단위 테스트, S-12)
- [ ] DLT 도착 → `payment_cancel_failures` RETRYING → 재시도 실패 6회 → EXHAUSTED + `business_payment_cancel_retry_exhausted_total` 증가, 성공 시 RESOLVED (Testcontainers 통합 테스트, PgClient를 실패하도록 대체)
- [ ] 관리자 API: ROLE_ADMIN이 아니면 403, 목록 조회·재처리 동작, 기록 없으면 404 (`@WebMvcTest`)
- [ ] `gradlew :serverC:test` 전체 통과 + 백엔드 E2E 회귀(구매 흐름) 통과 + 관리자 API 수동 호출 확인

### 비범위
- serverA·게이트웨이·serverB·cs-bot·프론트 변경 (PR②·③)
- 금액 불일치 즉시 망취소(`PaymentService.failWithAmountMismatch`)의 실패 재처리: 새 취소 API로 호출만 바꾸고 동작은 그대로 둔다
- serverA admin API 역할 검사 (BL-030)
- arch-snapshot 갱신은 BL-029 마지막 PR에서 한 번에 한다

### 단계별 작업 계획

#### 단계 1: 실패 기록 테이블 + 엔티티
- 변경 파일: `serverC/src/main/resources/db/migration/V3__payment_cancel_failures.sql`, `entity/PaymentCancelFailure.java`(+상태 enum), `repository/PaymentCancelFailureRepository.java`, 테스트 `repository/PaymentCancelFailureRepositoryTest.java`
- 변경 내용: 명세의 `payment_cancel_failures` 테이블(order_id UK, status, attempts, next_retry_at, last_error, 시각)과 `(status, next_retry_at)` 인덱스. 엔티티는 상태 전이를 도메인 메서드로 가진다 (`recordFailure`, `markResolved`, `markExhausted` 등). 재시도 대상 조회와 선점용 조건부 UPDATE 쿼리.
- 검증: `gradlew :serverC:test --tests "*PaymentCancelFailure*"` (Testcontainers MySQL로 Flyway 적용 포함)
- 롤백: 새 파일 삭제
- 예상 소요: 보통

#### 단계 2: PG 취소 멱등화
- 변경 파일: `service/PgClient.java`, `service/MockPgClient.java`, 새 `service/PgCancelResult.java`, `service/PaymentService.java`(호출부만), 테스트 `MockPgClientTest`(신규)·`PaymentServiceTest`(호출부 검증 수정)
- 변경 내용: `cancelPayments(List)` → `cancelPayment(orderId)`가 CANCELLED / ALREADY_CANCELLED를 돌려주고 실패는 예외로 던진다. MockPgClient는 취소한 orderId를 기억한다.
- 검증: `gradlew :serverC:test --tests "*MockPgClientTest" --tests "*PaymentServiceTest"`
- 롤백: `git checkout` 해당 파일
- 예상 소요: 짧음

#### 단계 3: 취소 처리 서비스 + 컨슈머 + `payment-cancelled` 발행
- 변경 파일: 새 `service/PaymentCancelService.java`, 새 `dto/PaymentCancelledMessage.java`, `kafka/PaymentCancelConsumer.java`, `repository/PaymentRepository.java`(조건부 상태 변경), `config/KafkaTopicConfig.java`(`payment-cancelled` 토픽), 테스트 `PaymentCancelServiceTest`(신규)·`PaymentCancelConsumerTest`(신규)
- 변경 내용: 결제 상태별 분기 — PAID면 PG 취소 후 같은 트랜잭션에서 CANCELLED + Outbox `payment-cancelled` + 실패 행이 있으면 RESOLVED / CANCELLED면 Outbox만 다시 저장 / 그 외(없음·PROCESSING·FAILED)면 PG 호출 없이 EXHAUSTED 기록 + 메트릭. PG 실패는 예외를 그대로 던져 기존 Kafka 재시도에 맡긴다. 컨슈머는 서비스에 위임만 한다.
- 검증: `gradlew :serverC:test --tests "*PaymentCancelServiceTest" --tests "*PaymentCancelConsumerTest"`
- 롤백: `git checkout` + 새 파일 삭제
- 예상 소요: 보통

#### 단계 4: DLT 기록 + 자동 재시도 스케줄러
- 변경 파일: `kafka/PaymentCancelConsumer.java`(DLT 핸들러), 새 `scheduler/PaymentCancelRetryScheduler.java`, `PaymentCancelService.java`(실패 기록·재시도 메서드), 설정(`application*.yml` — 간격 10분·최대 6회·스케줄 주기 1분), 테스트 `PaymentCancelRetrySchedulerTest`(단위) + `PaymentCancelRetryIntegrationTest`(Testcontainers, PgClient 대체)
- 변경 내용: DLT → RETRYING 기록(이미 있으면 갱신, attempts 유지). 스케줄러는 대상 조회 → 조건부 UPDATE로 선점 → 재시도. 실패 시 attempts+1, 6회면 EXHAUSTED + `business_payment_cancel_retry_exhausted_total`. 기존 `refund_fatal` 카운터는 DLT 진입 시 그대로 증가시킨다.
- 검증: `gradlew :serverC:test --tests "*PaymentCancelRetry*"`
- 롤백: `git checkout` + 새 파일 삭제
- 예상 소요: 김

#### 단계 5: 관리자 API
- 변경 파일: 새 `controller/PaymentAdminController.java`, 새 DTO(`dto/CancelFailureResponse.java`), 예외(`exception/AdminForbiddenException.java`, `CancelFailureNotFoundException.java`) + `GlobalExceptionHandler.java`, 테스트 `PaymentAdminControllerTest`(`@WebMvcTest`)
- 변경 내용: `GET /api/v1/payments/admin/cancel-failures?status=`, `POST /api/v1/payments/admin/cancel-failures/{orderId}/retry`. `X-User-Role != ROLE_ADMIN`이면 403. 재처리는 서비스의 동기 1회 재시도를 호출한다.
- 검증: `gradlew :serverC:test --tests "*PaymentAdminControllerTest"`
- 롤백: 새 파일 삭제 + `git checkout` 핸들러
- 예상 소요: 보통

#### 단계 6: 알람 규칙
- 변경 파일: `monitoring/prometheus/alert-rules.yml`, `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용: P0 `PaymentFatalError`의 expr을 `business_payment_cancel_retry_exhausted_total`로 바꾸고 설명 문구를 맞춘다.
- 검증: `helm template` (promotion-monitoring 차트) 통과 + YAML 확인
- 롤백: `git checkout`
- 예상 소요: 짧음

#### 단계 7: 전체 검증
- `gradlew :serverC:test` (백그라운드)
- 로컬 환경 기동 (serverC V3 마이그레이션 자동 적용 — **기동 전 사용자 확인**) → `backend-e2e` `npm test` 회귀 → 관리자 API 수동 호출 (403·200 빈 목록)
- 롤백: 해당 없음

### 리스크 및 대응
- 망취소 실패 행이 재대사 스케줄러(serverA)의 재발행과 겹침 → PG 멱등 + DLT 시 기존 행 갱신(attempts 유지)으로 처리 (명세 장애 시 동작)
- PR①만 배포된 동안 `payment-cancelled`를 소비하는 쪽이 없음 → REFUND_REQUESTED 주문이 아직 없어 무해 (명세)
- `PgClient` 시그니처 변경이 테스트 여러 곳에 영향 → 단계 2에서 호출부를 한 번에 정리
- 스케줄러가 여러 인스턴스에서 같은 행 실행 → 조건부 UPDATE 선점, 통합 테스트로 확인

### 의존성
- 새 Gradle 의존성 없음
- Debezium `payment-outbox-connector`는 `topic` 컬럼으로 라우팅 — 설정 변경 없음
