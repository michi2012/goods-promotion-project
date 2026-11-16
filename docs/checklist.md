# 체크리스트: 사용자 환불 PR②-b — serverA 환불 API·환불 완료 처리 + E2E (BL-029)

- 마지막 업데이트: 2026-11-16

## [완료] BL-029 PR②-b (PR #55)

### 진행 상황
- [x] 단계 1: `paid_at` 기록 (V4 + PAID 전이) — 마이그레이션, 단계별 승인
  - [x] 검증 통과 (`:serverA:test --tests "*OrderRepositoryTest" --tests "*SagaOrchestratorServiceTest"` → 3개·18개 통과. 테스트 전용 설정이 없어 main 설정(Flyway + `ddl-auto: validate`)으로 Testcontainers MySQL에 V1~V4 적용 후 `paid_at` 매핑 검증 통과)
- [x] 단계 2: user-service 조회 클라이언트
  - [x] 검증 통과 (`--tests "*UserQueryClientTest" --tests "*K8sDiscoveryConfigTest"` → 4개·2개 통과)
- [x] 단계 3: 환불 요청 서비스 + 예외
  - [x] 검증 통과 (`--tests "*RefundServiceTest"` → 11개 통과)
  - 계획 대비: `RefundResponse` DTO를 이 단계에서 생성(서비스 반환 타입), 환불 기간은 `saga.*`처럼 `@Value("${refund.period-days:7}")` 기본값(application.yaml 미변경)
  - 구현 중 확인: 동시 요청 패배 시 같은 트랜잭션 재조회는 MySQL REPEATABLE READ 스냅샷이라 PAID가 보임 → 새 트랜잭션에서 재조회 (코드 주석)
- [x] 단계 4: 환불 API + 계약
  - [x] 검증 통과 (`--tests "*RefundControllerTest" --tests "*RefundServiceTest" --tests "*OpenApiContractTest"` → 3개·12개·3개 통과)
  - 계획 대비: 명세에 코드가 없던 401을 `UNAUTHORIZED`/"로그인이 필요합니다"로 정함. `X-User-Id`는 `required=false`로 받아 서비스가 401 판단 (필수 헤더 누락은 기존 일반 핸들러에서 500이 됨). 계약에 `ErrorResponse` 스키마 추가
- [x] 단계 5: `payment-cancelled` 소비 → REFUNDED + 재고 복구
  - [x] 검증 통과 (`--tests "*PaymentCancelledConsumerTest" --tests "*RefundServiceTest"` → 3개·14개 통과. 재개 직후 단계 1~4 관련 7개 클래스 재확인도 통과)
- [x] 단계 6: 환불 E2E 시나리오
  - [x] 검증 통과 (정적 확인 — backend-e2e에 TypeScript가 없어 `npx playwright test --list`로 컴파일·수집 확인: 3개 시나리오. 실행은 단계 7)
- [x] 단계 7: 전체 빌드 + 로컬 E2E
  - [x] 검증 통과 (`:serverA:build` 3m 7s — 26개 클래스·121개 테스트 통과 / `mysql-user`·`user-service` 기동(기존 이미지) / server-a 재빌드 → Flyway `order` 스키마 3 → v4 적용 / `backend-e2e/ npm test` 3 passed)
  - E2E 데이터 전후: 재고 5 → 구매 후 3 → 환불 후 5, 동시 환불 2회 둘 다 200(REFUND_REQUESTED), serverB REFUNDED, serverC 결제 CANCELLED, 재구매 400 DUPLICATE_ORDER. 진단용 로그 집계로 serverA 환불 접수·완료 각 1회, serverC PG 취소 1회 확인
  - 첫 실행 실패 원인 (테스트 쪽, 수정 2회): ① 첫 시도는 모의 PG 결제 거절(1%, 정상 동작) → Playwright 기본 테스트 타임아웃 30초에 걸림 → 재시도 ② 재시도는 E2E가 user-service `username`을 고정값으로 보내 유니크 제약 위반(500) → 실행마다 고유값으로 수정 → `VARCHAR(20)` 초과 → 18자로 줄여 통과

### 최종 검증
- [ ] serverA 테스트 전체 통과
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [ ] 사용자에게 E2E 데이터 전후(주문 상태·재고)를 보여주고 확인받은 뒤 커밋 (e2e skill 7항)
- [x] 발견 사항을 백로그로 이전 (BL-038 환불 완료 실패 알람, BL-039 E2E 타임아웃, BL-040 user-service open-in-view)
- [ ] (위험 변경 — 결제·이벤트·마이그레이션) PR 생성 후 `/pr-review` 1회 — [필수] 반영·재리뷰 (`.claude/skills/pr-review`)

### 발견 사항 (작업 중 별도 처리 필요한 것)
- 단계 5: `business_refund_complete_failed_total`(payment-cancelled 처리 실패 → 주문이 REFUND_REQUESTED에 남음)에 알람 규칙 없음
- 단계 7: backend-e2e `playwright.config.ts`에 테스트 타임아웃 설정이 없어 기본 30초 — 시나리오의 폴링 대기(`E2E_FLOW_TIMEOUT_MS` 60초)보다 짧아 폴링이 끝나기 전에 테스트가 끊김 (기존 구매 흐름도 같음)
- 단계 7: user-service 로그 `spring.jpa.open-in-view is enabled by default` — CLAUDE.md "open-in-view: false 필수" 위반 (기존 설정)
- 단계 5 착수 시: `payment-cancelled.DLT` 토픽이 어디에도 없음 → 단계 5에 serverA `KafkaTopicConfig` 추가로 반영 (plan 갱신)
- 단계 5 착수 시: serverA·B·C DLT 라우팅이 원본과 같은 파티션 번호로 보내는데 DLT 토픽은 파티션 1 → 원본 파티션 1·2 실패 레코드가 DLT에 못 갈 가능성 → **별도 긴급 PR로 먼저 처리** (사용자 결정 2026-11-16). ②-b는 단계 1~4 완료 상태로 `git stash`에 보관 후 중단, 그 PR 머지 후 `git merge main` + stash 복원으로 재개 → **BL-037 #54 머지, 2026-11-16 재개** (3종 문서 충돌은 ②-b 내용으로 정리)
