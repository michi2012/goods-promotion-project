# 계획서: 모듈별 코드 흐름 문서화 (flow.md 4종 신규 작성)

- 작성일: 2026-09-11
- 관련 이슈/티켓: 없음

## 목표
기존 `docs/aiops-flow.md`, `docs/codebot-flow.md`, `docs/qa/cs-bot-flow.md`와 동일한 "진입점 → 클래스/메서드 단위 단계별 코드 흐름" 형식으로, 아직 flow.md가 없는 4개 영역(구매·Saga 핵심 흐름, user-service, gateway-service, frontend)의 문서를 신규 작성한다.

## 성공 기준
- [ ] `docs/purchase-saga-flow.md` 생성 — serverA/B/C 전체 구매→Saga→결제 흐름을 클래스/메서드 단위로 기술
- [ ] `docs/user-service-flow.md` 생성 — 회원가입·로그인(JWT 발급)·Reissue·결제수단 관리 흐름 기술
- [ ] `docs/gateway-service-flow.md` 생성 — JwtAuthFilter 인증·Rate Limiting·라우팅 흐름 기술
- [ ] `docs/frontend-flow.md` 생성 — OrderStatusPage 폴링 흐름(App.tsx 진입 → orval 생성 클라이언트 호출 → 상태 렌더링) 기술
- [ ] 각 문서는 실제 소스 코드(파일 경로 기준)를 읽고 작성 — 추측 서술 금지
- [ ] 기존 `README.md`/`arch-snapshot.md`와 내용이 모순되지 않는지 교차 확인

## 비범위 (Out of Scope)
- `docs/qa/interview-qa-*.md` 스타일(이력서 문장 Q&A)의 신규 문서는 만들지 않는다 (사용자가 flow.md 형식만 선택).
- 기존 aiops-flow.md / codebot-flow.md / cs-bot-flow.md 수정 없음.
- README.md, arch-snapshot.md 등 기존 문서 수정 없음 (내용 모순 발견 시에만 별도 보고, 임의 수정 안 함).
- 코드 변경 없음 — 순수 문서화 작업.

## 단계별 작업 계획

### 단계 1: docs/purchase-saga-flow.md 작성
- 대상 파일(읽기): serverA(PromotionController, PromotionService, RedisStockService, PurchaseKafkaConsumer, OrderCommandService, SagaOrchestratorService, SagaStateService, SagaResultConsumer, SagaTimeoutScheduler, OutboxEventService, PurchaseDltConsumer), serverB(OrderQueryController, OrderQueryService, OrderStatusKafkaConsumer, OrderStatusEventHandler, StockSnapshotConsumer), serverC(PaymentController, PaymentService, PaymentKafkaConsumer, PaymentCancelConsumer, OrderCompletedConsumer, PgClient/MockPgClient)
- 변경 파일: docs/purchase-saga-flow.md (신규)
- 내용 요약: 구매 요청 접수(Redis 3단계 방어) → Kafka 발행 → Saga 초기화 → 결제/상태갱신 병렬 처리 → SagaResultConsumer 판정 → 성공/실패 분기(보상 트랜잭션) → 타임아웃 처리까지 클래스·메서드 단위로 추적
- 검증 방법: 문서 내 언급된 클래스명·메서드명·토픽명이 실제 소스에 존재하는지 grep으로 교차 확인
- 롤백 방법: 신규 파일 삭제
- 예상 소요: 김 (3개 모듈, 가장 핵심 로직)

### 단계 2: docs/user-service-flow.md 작성
- 대상 파일(읽기): UserController, UserService, LoginFilter, JWTUtil, ReissueController, CustomUserDetailsService, SecurityConfig, PaymentMethodController, PaymentMethodService, InternalUserController
- 변경 파일: docs/user-service-flow.md (신규)
- 내용 요약: 회원가입 → 로그인(LoginFilter → JWT 발급 → refresh 쿠키) → Reissue → 프로필/결제수단 CRUD → codebot/cs-bot이 호출하는 Internal API까지
- 검증 방법: 클래스명·엔드포인트가 실제 소스와 일치하는지 확인
- 롤백 방법: 신규 파일 삭제
- 예상 소요: 보통

### 단계 3: docs/gateway-service-flow.md 작성
- 대상 파일(읽기): JwtAuthFilter, RateLimiterConfig, application.yml(라우팅 테이블)
- 변경 파일: docs/gateway-service-flow.md (신규)
- 내용 요약: 요청 진입 → 화이트리스트 판별 → JWT 검증 → X-User-Id/X-User-Role 헤더 주입 → Redis 토큰버킷 Rate Limiting → 하위 서비스 라우팅
- 검증 방법: 라우팅 규칙이 arch-snapshot.md 표와 일치하는지 확인
- 롤백 방법: 신규 파일 삭제
- 예상 소요: 짧음 (파일 수 적음)

### 단계 4: docs/frontend-flow.md 작성
- 대상 파일(읽기): App.tsx, OrderStatusPage.tsx, api/generated/order-query-controller, api/axios-instance.ts
- 변경 파일: docs/frontend-flow.md (신규)
- 내용 요약: 진입 → orval 생성 API 클라이언트로 serverB 폴링(Exponential Backoff) → 상태별 렌더링(PAID/FAILED/EXPIRED/PENDING)
- 검증 방법: README에 서술된 폴링 정책(2s 초기 대기, 2→4→8→16→32s, 최대 6회, 60s 타임아웃)과 실제 코드 일치 확인
- 롤백 방법: 신규 파일 삭제
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: 소스가 방대해 문서가 README/arch-snapshot과 중복될 위험 → 대응: flow.md는 "코드를 따라가는 절차" 서술에 집중하고, 설계 이유/트레이드오프는 README 링크로 대체
- 리스크 2: 클래스/메서드명 오기재(환각) → 대응: 각 단계 완료 후 Grep으로 실존 여부 교차 검증

## 의존성
없음 (순수 문서 생성, 코드 변경 없음)

---

# 계획서 추가: aiops-flow.md / codebot-flow.md 보강 (심화)

- 작성일: 2026-09-11
- 관련 이슈/티켓: 없음
- 선행 작업: 위 "모듈별 코드 흐름 문서화(flow.md 4종 신규 작성)" — 아직 커밋 전, 같은 문서화 세션의 연장.

## 목표
기존 `docs/aiops-flow.md`(127줄)·`docs/codebot-flow.md`(89줄)는 이미 상세하지만, aiops 19개 파일·codebot 14개 파일 전체를 실제로 읽고 대조한 적은 없다. 두 모듈의 전체 소스를 정독해 누락된 클래스/메서드/설정값을 찾아 기존 문서 구조를 유지한 채 보강한다.

## 성공 기준
- [ ] aiops `src/main/java` 19개 파일 전부 읽음
- [ ] codebot `src/main/java` 14개 파일 전부 읽음
- [ ] `docs/aiops-flow.md`에 도구별(ObservabilityTools/KubernetesTools/DltTools) 실제 메서드 목록, `ActionApprovalService`/`AlertDeduplicationService`/`IntentClassifierService`/`LinearAuditService`의 실제 동작(TTL/알고리즘/프롬프트 구조)이 반영됨
- [ ] `docs/codebot-flow.md`에 도구별(CodeSearchTools/ObservabilityTools/KubernetesTools/DataQueryTools/LinearTools/PullRequestTools) 실제 메서드 목록과 세부 동작이 반영됨
- [ ] 기존 문서 서술과 실제 코드가 다른 부분은 수정, 근거 없는 서술은 없음(grep 교차 확인)
- [ ] 기존 문서의 구조(섹션 순서·제목)는 유지, 내용만 보강

## 비범위 (Out of Scope)
- 문서 전체 재작성/구조 변경 없음 (사용자가 "기존 구조 유지 + 보강" 선택)
- 다른 flow.md(purchase-saga/user-service/gateway/frontend)는 이미 완료, 재작업 없음
- 코드 변경 없음

## 단계별 작업 계획

### 단계 1: docs/aiops-flow.md 보강
- 대상 파일(읽기): AlertDeduplicationService, ActionApprovalController/Service, PrometheusWebhookController, SlackNotificationService, LinearAuditService, ChatMemoryConfig, CodebotClient, SlackEventController, CanaryRolloutScheduler, IntentClassifierService, RouterService, SlackBotClient, InfraChatAgentService, SlackInteractiveController, KubernetesTools, ObservabilityTools, AiOpsAgentService, RestClientConfig, DltTools
- 변경 파일: docs/aiops-flow.md (수정)
- 내용 요약: 각 도구 클래스의 실제 메서드 시그니처·트리거 조건·설정 기본값을 표로 보강, 기존 서술과 다른 부분 정정
- 검증 방법: 문서에 추가한 클래스/메서드명 grep 교차 확인
- 롤백 방법: git diff로 되돌림
- 예상 소요: 김

### 단계 2: docs/codebot-flow.md 보강
- 대상 파일(읽기): InvestigationController, InvestigationRequest/Response, ChatMemoryConfig, RestClientConfig, KubernetesTools, ObservabilityTools, CodebotAgentService, DataQueryDataSourceConfig, CodeSearchTools, DataQueryTools, LinearTools, PullRequestTools
- 변경 파일: docs/codebot-flow.md (수정)
- 내용 요약: 도구별 실제 메서드·화이트리스트 규칙·PR 생성 절차 세부사항 보강
- 검증 방법: grep 교차 확인
- 롤백 방법: git diff로 되돌림
- 예상 소요: 보통

## 리스크 및 대응
- 리스크: 기존 문서와 실제 코드가 달라 서술을 정정해야 하는 경우 → 대응: 정정 사실을 명확히 표시하고 근거(파일 경로) 남김

## 의존성
없음
