# 맥락 노트: CS 자동 응대 챗봇 Phase1 — 백엔드 cs-bot 모듈

---

## [진행 중] cs-bot Helm 차트 추가 맥락

### 왜 aiops 패턴을 따랐는가
- aiops: AI 기능 단일 목적 서비스, Eureka 등록, Kafka 없음, HPA 없음, VirtualService/DestinationRule 있음 → cs-bot과 거의 동일 프로파일
- codebot: VirtualService/DestinationRule 없음 → GitHub webhook 수신 전용, Istio mesh 내 L7 라우팅 불필요. cs-bot은 gateway에서 lb://cs-bot으로 Eureka 라우팅되므로 Istio mesh 라우팅 정책 적용 대상
- 따라서 cs-bot = aiops 패턴 (VirtualService/DestinationRule O) + codebot의 rbac (SA only, ClusterRole 없음)

### 왜 HPA를 제외했는가
Phase1 목표는 Helm 등록 완료. cs-bot은 Gemini API 호출로 I/O 집중이지만 초기 트래픽이 낮으므로 replicas: 1로 시작. Phase2에서 실측 트래픽 기반으로 추가.

### 환경변수 매핑
| app yaml 키 | 환경변수명 | values.yaml 키 |
|---|---|---|
| spring.ai.google.genai.api-key | AI_API_KEY | csBot.aiApiKey |
| eureka.client.service-url.defaultZone | EUREKA_DEFAULT_ZONE | csBot.eurekaDefaultZone |
| spring.kafka.bootstrap-servers | SPRING_KAFKA_BOOTSTRAP_SERVERS | csBot.kafka.bootstrapServers |
| linear.api-key | LINEAR_API_KEY | csBot.linearApiKey |
| linear.team-id | LINEAR_TEAM_ID | csBot.linearTeamId |
| management.otlp.tracing.endpoint | OTLP_ENDPOINT | csBot.otlpEndpoint |
| spring.profiles.active | SPRING_PROFILES_ACTIVE | csBot.springProfilesActive |

---

## [완료] CS 봇 도구 5종 추가 맥락

### 왜 도구를 3개로 통합했는가
5개 도구를 요청했지만 3개로 통합함.
- 도구가 세분화되면 LLM이 어떤 도구를 호출해야 할지 혼란 → Tool description이 겹치면 잘못된 도구를 선택하거나 둘 다 호출하는 문제 발생
- `getOrderDetail` = 주문 상세(결제+상품명+주문상태) → 하나의 orderId에 대한 완전한 정보
- `diagnosePaymentIssue` = 진단 개념으로 불일치+중복을 한 번의 분석으로 처리

### 왜 serverA에 GET API를 추가했는가
goodsId 숫자만 반환하면 CS 상담에서 의미가 없음 ("상품ID 3 주문을 취소하시겠어요?"는 고객이 이해 못 함).
GoodsService와 GoodsRepository가 이미 존재해서 컨트롤러 메서드 1개, 서비스 메서드 1개 추가로 충분.

### 왜 DLT 조회 + retryDlt()를 Phase1에 포함했는가
당초 Phase2로 분류했으나 "결제됐는데 주문이 안 됐어" 시나리오 지원을 위해 Phase1에 포함.
- `purchase_events.DLT`는 구매 실패(재고 복구 실패) 시 메시지가 쌓이는 토픽이며, `PurchaseDltConsumer`가 `DeadLetter` 엔티티로 DB에 저장함.
- `DeadLetter` 엔티티에 `userId` 없음 → cs-bot에서 먼저 `getMyOrders()`로 본인 결제 목록을 조회해 해당 `orderId`가 본인 것인지 확인 후 DLT 조회.
- `AdminController`에 기존 retry POST는 있었지만 조회 GET이 없었음 → `GET /api/v1/admin/dlt/orders/{orderId}` 추가.
- serverA에 Spring Security 미설정 → `/api/v1/admin/**` 내부 서비스 호출 가능(별도 인증 헤더 불필요).
- retryDlt()는 재고 강제 복구(`increaseStockAtomically`) 후 DLT 상태를 RESOLVED로 변경. cs-bot이 이를 호출하면 "재처리됨" 응답을 고객에게 즉시 안내 가능.

### 왜 payment-cancel 재발행을 별도 도구로 만들지 않았는가
cs-bot의 `requestRefund(orderId)` = Kafka `payment-cancel` 토픽에 produce. "취소 했는데 환불이 안 됐어" 상황에서 고객이 `requestRefund`를 재호출하면 그 자체가 재발행임. 별도 도구를 추가하면 동일 행위에 두 개의 진입점이 생겨 LLM이 혼란을 겪는다. SYSTEM_PROMPT에서 "환불 상태 PAID이면 requestRefund를 다시 시도하도록" 안내하는 것으로 대체.

### 왜 PaymentCancelConsumer를 수정했는가
`trackRefundStatus` 도구의 핵심은 "취소 요청 후 실제 완료됐는지 확인"인데, MockPgClient가 DB status를 CANCELLED로 변경하지 않아 항상 PAID를 반환했음.
이것은 Phase1에서 이미 발견된 버그였고("MockPgClient.cancelPayments()가 취소 후 DB status를 변경하지 않아"), trackRefundStatus 구현을 위해 이번에 함께 수정.

### 중복 결제 감지 기준 (10분)
동일 goodsId + 동일 userId + 10분 이내 2건 = 실수 결제로 판단.
- 더 짧으면(1분) 의도적 재구매도 잡음. 더 길면(1시간) 선물용 동시 구매도 차단.
- 감지 후 자동 취소 아닌 제안만 → 고객이 "예"라고 하면 requestRefund 호출하도록 LLM에게 위임.

### serverB 주의사항
serverB는 Redis(redis-b)에서 주문 상태를 조회하는 Read Model(CQRS View). Kafka Consumer가 주문 이벤트를 수신해야만 Redis에 데이터가 쌓임. 로컬에서 serverB만 기동해도 Redis에 주문 데이터가 없으면 "NOT_FOUND" 반환. `diagnosePaymentIssue`에서 NOT_FOUND는 불일치로 간주하지 않음.

---

## 왜 이 방식을 선택했는가

### 신규 모듈로 분리
aiops는 인프라/코드 챗봇(운영자 대상, Slack 채널, ACTION 승인 게이트) 책임을 가진다. CS 챗봇은 고객(인증된 최종 사용자) 대상이며 보안 경계와 SYSTEM_PROMPT, 도구셋이 완전히 다르다. 따라서 aiops를 수정/확장하지 않고 `cs-bot`이라는 별도 모듈로 신설한다. 단, Router-Worker 패턴(ChatClient + SYSTEM_PROMPT + `@Tool` + `ChatMemory`)은 aiops `InfraChatAgentService`/`ChatMemoryConfig`를 코드로 그대로 복사하지 않고 **패턴만 동일하게** 구현한다(모듈 간 의존 없음).

### Phase 분할
요청 범위(신규 모듈 골격 + identity 매핑 + 조회 3종 + 환불/취소 ACTION + 에스컬레이션 + 프론트엔드 위젯)가 `/plan` 1회 분량(최대 7단계)을 초과한다. Phase1=백엔드 cs-bot 전체(이 문서의 범위), Phase2=프론트엔드 채팅 위젯으로 분리한다. 사용자 합의됨.

### Identity 매핑 문제와 해법
JWT/`X-User-Id` 헤더는 `User.userId`(String, 로그인ID)를 담고 있지만, 도구가 호출해야 하는 기존 API들(`PaymentResponse.userId`, `UserProfileResponse.id`)은 모두 `User.id`(Long, 숫자 PK)를 기준으로 한다. 두 값을 잇는 기존 엔드포인트가 없었다.

해법: `GET /internal/api/users/{userId}`(gateway 미노출, 서비스 간 전용)가 반환하는 `InternalUserResponse`에 `Long id` 필드를 추가한다(추가 전용, 하위호환 유지). cs-bot은 요청 시작 시 1회 `loginId → id`를 변환하고, 변환된 `id`로 `PaymentResponse`/`UserProfileResponse`를 조회한다. `UserService.getUserProfile(Long id, String authenticatedUserId)`의 `validateOwner(user, authenticatedUserId)`가 `authenticatedUserId`(loginId)와 조회 대상 `User.userId`를 비교하므로, cs-bot이 `X-User-Id: <loginId>`를 그대로 전달하면 별도 인증 계층 변경 없이 기존 본인 확인 로직을 통과한다.

### 환불/취소 처리 = 기존 Kafka `payment-cancel` 토픽 재사용 (신규 동기 API 대신)
serverA `KafkaTopicConfig`에 이미 `payment-cancel` 토픽(3 partitions/1 replica)이 선언되어 있고, serverC `PaymentCancelConsumer`가 이를 소비해 `PgClient.cancelPayments()`로 보상 트랜잭션을 수행한다. cs-bot이 별도의 동기 취소 API를 새로 만들면 상태 변경 경로가 두 개(Saga 비동기 경로 + 신규 동기 경로)로 분기되어 일관성 위험이 생긴다. **단일 mutation 경로 원칙**에 따라 cs-bot은 기존 토픽에 `PaymentCancelMessage(orderId)`를 produce만 하고, "접수되었습니다" 형태의 비동기 응답을 반환한다. 처리 결과는 후속으로 `getMyOrders`(상태 조회 도구)를 통해 확인 가능하다.

### 승인 정책 = 즉시 처리 + 상태 가드(멱등성)
aiops의 ACTION(Pod 재시작, HPA 패치 등)은 Slack `[승인]`/`[거절]` 게이트를 거친다 — 공유 인프라에 영향을 주는 작업이라 운영자 승인이 필요하기 때문이다. 반면 고객이 본인 주문을 취소하는 행위의 blast radius는 본인 1명으로 한정되며, 위험 등급이 근본적으로 다르다. 따라서 Phase1에서는 `requestRefund(orderId)`가 (1) 해당 주문이 요청자 본인 소유인지, (2) 현재 `status`가 취소 가능한 상태(`SUCCESS`)인지만 확인 후 즉시 처리한다. 금액/리스크 기반 분기, 검토 큐는 **명시적으로 비범위**로 둔다 — 향후 확장 시 이 가드 조건에 분기 로직을 추가하는 지점이 된다.

### 세션 저장 = 인메모리 ChatMemory, replica=1 가정
Spring AI가 기본 제공하는 `InMemoryChatMemoryRepository` + `MessageWindowChatMemory`(aiops `ChatMemoryConfig`에서 이미 사용 중, 신규 의존성 불필요)를 그대로 사용한다. Redis 기반 세션 저장은 신규 인프라(Redis 연결, 장애 처리)를 추가해야 하는데, Phase1에서는 cs-bot 인스턴스 1개(replica=1)를 운영 전제로 하므로 인메모리로 충분하다. **이 가정은 명시적 제약**이며, 멀티 인스턴스로 확장할 때는 `ChatMemoryRepository` 구현체를 Redis 기반으로 교체해야 한다(아래 "핵심 제약" 참고).

### 보안: 도구는 사용자 식별자를 LLM 입력으로 받지 않음
모든 `@Tool` 메서드는 `userId`/주문 소유자 등을 LLM이 생성한 파라미터로 받지 않는다. `CsUserContext`(`@RequestScope`)가 `X-User-Id` 헤더에서 얻은 loginId를 보관하고, 필요 시 숫자 `id`로 변환해 캐싱한다. 도구는 이 컨텍스트에서만 "본인" 식별자를 가져온다 — 프롬프트 인젝션으로 타인의 데이터를 조회/조작하는 경로를 원천 차단한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: serverA에 신규 "내 주문 조회" API 추가
- 무엇: serverA `PromotionController`에 `GET /api/v1/orders/my` 같은 신규 엔드포인트를 추가해 구매 내역을 조회.
- 왜 안 썼나: serverC의 기존 `GET /api/v1/payments/users/{userId}`가 반환하는 `PaymentResponse`에 구매/결제/환불·취소 상태가 모두 포함되어 있어, 신규 API는 중복이다. 단일 조회 도구(`getMyOrders`)로 통합 가능.

### 대안 B: 환불/취소 처리를 신규 동기 API로 구현 (예: serverC `POST /api/v1/payments/{orderId}/cancel`)
- 무엇: cs-bot이 동기 REST 호출로 즉시 취소를 트리거.
- 왜 안 썼나: 기존 Saga 보상 트랜잭션(`payment-cancel` 토픽 → `PaymentCancelConsumer`)과 별도의 mutation 경로가 추가되어 상태 불일치 위험이 생기고, 기존 DLT/메트릭/트레이싱 인프라를 재사용하지 못한다.

### 대안 C: 대화 세션을 Redis에 저장
- 무엇: `ChatMemoryRepository`를 `RedisChatMemoryRepository`(또는 직접 구현)로 교체.
- 왜 안 썼나: replica=1 가정 하에서는 인메모리로 충분하고, Redis 연결 설정/장애 처리라는 새 운영 부담을 Phase1에 추가할 이유가 없다. Spring AI의 `InMemoryChatMemoryRepository`는 aiops에서 이미 검증된 패턴이며 신규 의존성도 없다.

### 대안 D: 환불 ACTION에 aiops식 Slack 승인 게이트 적용
- 무엇: `requestRefund`를 `propose`/`approve` 2단계 + Slack 인터랙션으로 구현.
- 왜 안 썼나: 본인 주문 취소는 blast radius가 본인 1인으로 제한되어, 공유 인프라에 영향을 주는 인프라 ACTION과 리스크 등급이 다르다. 즉시 처리 + 상태 가드(멱등성)로 충분하며, 별도 승인자가 필요하지 않다.

## 기존 코드베이스 컨벤션
- 모듈 구조: aiops 참고 (`build.gradle`, `application.yaml`, 패키지 `aiops.aiops.*`) → cs-bot은 `csbot.csbot.*` 패키지로 동일 구조 적용 (가정 — 단계 1에서 모듈 생성 시 확정).
- ChatClient 패턴: `aiops/src/main/java/aiops/aiops/router/InfraChatAgentService.java` — `SYSTEM_PROMPT` 상수 + `ChatClient.Builder` + `ChatMemory.CONVERSATION_ID` + `.tools(...)` + `chat(conversationId, message)`.
- ChatMemory 설정: `aiops/src/main/java/aiops/aiops/router/ChatMemoryConfig.java` — `InMemoryChatMemoryRepository` + `MessageWindowChatMemory`(maxMessages=20), 빈 그대로 동일 구조로 복제.
- 인프라 클라이언트: `aiops/src/main/java/aiops/aiops/router/CodebotClient.java` + `RestClientConfig.internalServiceClientBuilder()`(DiscoveryClient/Eureka 기반, 타임아웃 3s/60s) — cs-bot의 `CsBotClient`/`RestClientConfig`가 동일 패턴.
- Linear 연동: `aiops/src/main/java/aiops/aiops/linear/LinearAuditService.java` — `@Qualifier("linearClient") RestClient` + GraphQL `issueCreate` mutation + `linear.team-id`/`linear.api-key` 설정, 실패 시 예외 대신 안내 문자열 반환. cs-bot의 `CsEscalationService`가 독립 구현으로 동일 패턴 적용.
- 테스트 구조: `aiops/src/test/java/aiops/aiops/**/*Test.java` — Mockito 기반 단위 테스트.

## 관련 파일/위치
- `user-service/src/main/java/com/example/user_service/dto/response/InternalUserResponse.java` — Identity 매핑의 핵심. `Long id` 필드 추가 대상.
- `user-service/src/main/java/com/example/user_service/controller/InternalUserController.java` — `GET /internal/api/users/{userId}`, gateway 미노출, cs-bot의 identity 변환 호출 대상.
- `user-service/src/main/java/com/example/user_service/controller/UserController.java` — `GET /api/users/{id}`, `X-User-Id` 헤더 기반 본인 확인(`validateOwner`).
- `serverC/.../controller` — `GET /api/v1/payments/users/{userId}` (`PaymentResponse`, 구매/결제/환불·취소 상태 통합 소스).
- `serverA/src/main/java/promotion/serverA/config/KafkaTopicConfig.java` — `payment-cancel` 토픽 선언(기존, 변경 없음).
- `serverC/src/main/java/promotion/serverC/kafka/PaymentCancelConsumer.java` — `payment-cancel` 토픽 소비자(기존, 변경 없음). cs-bot의 produce가 여기로 흐른다.
- `aiops/src/main/java/aiops/aiops/router/{ChatMemoryConfig,InfraChatAgentService,CodebotClient}.java`, `aiops/src/main/java/aiops/aiops/linear/LinearAuditService.java`, `aiops/build.gradle`, `aiops/src/main/resources/application.yaml` — cs-bot 구현 템플릿(패턴 참고용).

## 외부 참조
없음

## 핵심 제약 — Identity 매핑
- JWT/`X-User-Id` = `User.userId`(String, 로그인ID).
- `PaymentResponse.userId`(serverC) / `UserProfileResponse.id`(user-service) = `User.id`(Long, 숫자 PK).
- cs-bot은 매 요청마다 `GET /internal/api/users/{loginId}` → `InternalUserResponse.id`(신규 필드)로 변환 후, 변환된 `id`로 위 API들을 호출한다. `CsUserContext`가 요청 범위 내에서 이 변환 결과를 캐싱한다.

## 핵심 제약 — 세션, replica=1
- Phase1은 cs-bot 인스턴스 1개(replica=1)를 전제로 인메모리 `ChatMemory`를 사용한다.
- 멀티 인스턴스로 확장할 경우, 같은 `conversationId`의 후속 요청이 다른 인스턴스로 라우팅되면 대화 컨텍스트가 끊긴다. 이때는 Redis 등 외부 저장소 기반 `ChatMemoryRepository`로 교체해야 한다 — Phase1 범위 밖.

## 의존성 확인 필요 — `spring-kafka`
cs-bot이 `payment-cancel` 토픽에 produce하려면 `spring-kafka`(producer only) 의존성이 필요하다. aiops에는 이 의존성이 없고 cs-bot에 새로 추가되는 것이므로, CLAUDE.md의 "신규 Gradle 의존성 추가 시 사용자 확인" 규칙 대상이다 — `docs/plan.md`의 "의존성"/"리스크 1"에 명시했다.
