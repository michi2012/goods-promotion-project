# 계획서: aiops DLT 자동 재처리 도구 추가

- 작성일: 2026-06-16
- 관련 이슈/티켓: 없음

## 목표
Prometheus가 DLT 누적을 감지하면 aiops가 자동으로 재처리 가능한 DLT를 복구하고, 스키마 오류 등 재처리 불가 건은 Slack으로 수동 처리를 안내한다.

## 성공 기준
- [ ] serverA `GET /api/v1/admin/dlt` 호출 시 UNRESOLVED DLT 목록 반환
- [ ] serverA Actuator `/actuator/prometheus`에 `business_dead_letter_unresolved_total` 게이지 노출
- [ ] `PurchaseDltAccumulated` Prometheus 알람이 alert-rules.yml에 추가됨
- [ ] aiops가 `PurchaseDltAccumulated` 알람 수신 시 retryable DLT 자동 재처리 후 Slack 보고
- [ ] non-retryable(orderId="UNKNOWN" 또는 goodsId=null) DLT는 Slack 수동 처리 알림
- [ ] `.\gradlew.bat :serverA:compileJava :aiops:compileJava` 빌드 통과
- [ ] `helm template promotion-app ./helm/promotion-app` 렌더링 통과

## 비범위 (Out of Scope)
- Slack 승인 버튼(proposeAction) 패턴 — 재처리 가능 건은 즉시 자동 실행
- Linear 이슈 자동 생성 — Slack 알림으로 충분
- 스키마 오류 DLT의 자동 보정 (메시지 재파싱 등) — 수동 처리 대상
- docker-compose E2E (Prometheus → Alertmanager → aiops 전체 흐름) — 인프라 의존

## 단계별 작업 계획

### 단계 1: serverA — DeadLetterRepository 확장 + AdminController `GET /dlt`
- 변경 파일: `serverA/.../repository/DeadLetterRepository.java`, `service/dlt/DeadLetterService.java`, `controller/AdminController.java`
- 변경 내용: `findAllByStatus(DltStatus)` 쿼리 메서드 추가, `listUnresolved()` 서비스 메서드 추가, `GET /api/v1/admin/dlt` 엔드포인트 추가
- 검증 방법: `.\gradlew.bat :serverA:compileJava`
- 롤백 방법: git checkout serverA/...
- 예상 소요: 짧음

### 단계 2: serverA — Prometheus 게이지 메트릭
- 변경 파일: `serverA/.../config/MetricsConfig.java` (NEW)
- 변경 내용: `MeterRegistry.gauge("business_dead_letter_unresolved_total", deadLetterRepository, repo -> repo.countByStatus(DltStatus.UNRESOLVED))` 등록. `DeadLetterRepository`에 `countByStatus` 추가.
- 검증 방법: `.\gradlew.bat :serverA:compileJava`
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

### 단계 3: alert-rules.yml — PurchaseDltAccumulated 알람 추가
- 변경 파일: `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용: `Tier1-Business-Impact-SLO` 그룹에 `PurchaseDltAccumulated` 알람 추가 (`business_dead_letter_unresolved_total > 0`, for: 1m, severity: critical, tier: P1)
- 검증 방법: yaml 파일 육안 확인
- 롤백 방법: git checkout helm/...
- 예상 소요: 짧음

### 단계 4: aiops — serverA RestClient + DltTools.java
- 변경 파일: `aiops/src/main/resources/application.yaml`, `aiops/.../config/RestClientConfig.java`, `aiops/.../tools/DltTools.java` (NEW)
- 변경 내용: application.yaml에 `services.server-a.url` 추가. RestClientConfig에 `serverAAdminClient` Bean 추가. DltTools에 `listUnresolvedDlt()`, `retryDlt(Long dltId)` 두 메서드 구현.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: DltTools.java 삭제, yaml/config 원복
- 예상 소요: 보통

### 단계 5: aiops — AiOpsAgentService DltTools 등록 + SYSTEM_PROMPT
- 변경 파일: `aiops/.../agent/AiOpsAgentService.java`
- 변경 내용: `tools(observabilityTools, kubernetesTools, dltTools)` 추가. SYSTEM_PROMPT에 `PurchaseDltAccumulated` 시나리오 추가 (listUnresolvedDlt → 분류 → retryable이면 retryDlt 자동 호출 → Slack 보고, non-retryable이면 수동 처리 안내).
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: git checkout aiops/...
- 예상 소요: 짧음

### 단계 6: Helm aiops 업데이트 + 테스트
- 변경 파일: `helm/promotion-app/values.yaml`, `helm/promotion-app/templates/aiops/deployment.yaml`, `serverA/.../controller/AdminControllerTest.java`, `aiops/.../tools/DltToolsTest.java` (NEW)
- 변경 내용: aiops values에 `serverAUrl` 추가, deployment에 `SERVER_A_URL` 환경변수 추가. AdminController `GET /dlt` 테스트, DltTools 단위 테스트.
- 검증 방법: `helm template promotion-app ./helm/promotion-app` + `.\gradlew.bat :serverA:compileTestJava :aiops:compileTestJava`
- 롤백 방법: git checkout helm/...
- 예상 소요: 보통

## 리스크 및 대응
- 리스크: MeterRegistry.gauge Supplier가 매 scrape마다 DB 조회 → 대응: countByStatus는 단순 COUNT 쿼리, Prometheus 기본 scrape 15s로 부하 미미
- 리스크: aiops → serverA 네트워크 오류 시 도구 실패 → 대응: DltTools에서 예외 catch 후 "조회 실패" 문자열 반환 (기존 ObservabilityTools 패턴 동일)
- 리스크: retryDlt 실패(GoodsNotFoundException 등) → 대응: 예외 catch 후 Slack에 실패 내용 포함 에스컬레이션

## 의존성
- serverA 기동 중이어야 DltTools 동작
- Prometheus → Alertmanager → aiops webhook 파이프라인은 기존 구성 유지

---

# 계획서: CS 자동 응대 챗봇 Phase1 — 백엔드 cs-bot 모듈

---

## [진행 중] cs-bot Helm 차트 추가

- 작성일: 2026-06-16
- 관련 이슈/티켓: 없음

## 목표
기존 promotion-app Helm 차트에 cs-bot 서비스를 추가한다. aiops 패턴을 따르며, HPA 없이 replicas: 1로 시작한다.

## 성공 기준
- [ ] `helm template promotion-app ./helm/promotion-app` 오류 없이 렌더링
- [ ] cs-bot Deployment, Service, VirtualService, DestinationRule, ServiceAccount 리소스 정상 생성
- [ ] `values.yaml`에 `csBot` 섹션 추가 (image, port, 환경변수 전체)
- [ ] 민감 환경변수(AI_API_KEY, LINEAR_API_KEY, LINEAR_TEAM_ID)는 `--set`으로 주입하도록 기본값 `""` 처리

## 비범위 (Out of Scope)
- HPA (Phase2에서 트래픽 확인 후 추가)
- 카나리 배포 (serverA/B/C만 해당)
- Ingress 변경 (모든 트래픽은 gateway-service 경유)
- Profiler sidecar (aiops/codebot도 없음)

## 단계별 작업 계획

### 단계 1: values.yaml에 csBot 섹션 추가
- 변경 파일: `helm/promotion-app/values.yaml`
- 변경 내용: `codebot` 섹션 뒤에 `csBot` 섹션 추가. port 8089, replicas 1, 환경변수 7개
- 검증 방법: 파일 확인 (helm template은 단계 4에서)
- 롤백 방법: git checkout helm/promotion-app/values.yaml
- 예상 소요: 짧음

### 단계 2: deployment.yaml + service.yaml 생성
- 변경 파일: `helm/promotion-app/templates/cs-bot/deployment.yaml`, `service.yaml`
- 변경 내용: aiops 패턴 복사 후 csBot 변수로 교체. 환경변수 7개 주입
- 검증 방법: 파일 확인
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

### 단계 3: virtualservice.yaml + destinationrule.yaml 생성
- 변경 파일: `helm/promotion-app/templates/cs-bot/virtualservice.yaml`, `destinationrule.yaml`
- 변경 내용: aiops 패턴 복사 후 csBot 변수로 교체
- 검증 방법: 파일 확인
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

### 단계 4: rbac.yaml 생성 + helm template 검증
- 변경 파일: `helm/promotion-app/templates/cs-bot/rbac.yaml`
- 변경 내용: ServiceAccount만 생성 (ClusterRole 없음)
- 검증 방법: `helm template promotion-app ./helm/promotion-app` 렌더링 성공
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: `helm template` 없이는 EKS에서만 오류 발견 → `helm template` 렌더링 필수
- 리스크: AI_API_KEY 등 민감값이 기본값에 노출 → 기본값 `""` 처리로 방지

## 의존성
- 기존 `promotion-app` Helm 차트 구조
- cs-bot Docker 이미지 (ECR push는 배포 시 별도 진행)

---

## [완료] CS 봇 도구 5종 추가 (Phase1 보완)

- 작성일: 2026-06-16
- 관련 이슈/티켓: 없음

## 목표
cs-bot이 단순 조회를 넘어 실제 예외 상황(주문-결제 불일치, 중복 결제, 환불 지연)을 감지하고 고객에게 의미 있는 답변을 제공할 수 있도록 도구 3개를 추가한다.

## 성공 기준
- [ ] `getOrderDetail(orderId)` 호출 시 결제정보(serverC) + 상품명(serverA) + 주문상태(serverB)를 통합해 반환
- [ ] `trackRefundStatus(orderId)` 호출 시 status CANCELLED=완료, PAID=처리 중 구분 반환
- [ ] `diagnosePaymentIssue()` 호출 시 불일치 또는 10분 이내 중복 결제 감지
- [ ] `PaymentCancelConsumer`가 취소 후 DB status를 CANCELLED로 업데이트
- [ ] serverA `GET /api/v1/goods/{goodsId}` 호출 시 상품명과 재고 반환
- [ ] `checkAndRetryPurchaseDlt(orderId)` 호출 시 UNRESOLVED DLT 발견 → retryDlt() 호출 후 재처리 결과 반환
- [ ] `.\gradlew.bat :cs-bot:build :serverA:build :serverC:build` 전체 빌드 통과

## 비범위 (Out of Scope)
- 회원 정보 수정 (Phase2)
- 프론트엔드 변경
- serverA/serverB docker-compose 기동 (E2E 전 사용자 직접 기동)
- payment-cancel 재발행 별도 도구 → `requestRefund(orderId)` 재호출이 재발행과 동일하므로 SYSTEM_PROMPT 안내로 대체

## 단계별 작업 계획

### 단계 1: serverA API 추가 (상품 조회 + DLT 조회)
- 변경 파일:
  - `serverA/src/main/java/promotion/serverA/dto/response/GoodsResponse.java` (신규)
  - `serverA/src/main/java/promotion/serverA/dto/response/DltResponse.java` (신규)
  - `serverA/src/main/java/promotion/serverA/service/GoodsService.java`
  - `serverA/src/main/java/promotion/serverA/controller/GoodsController.java`
  - `serverA/src/main/java/promotion/serverA/repository/DeadLetterRepository.java`
  - `serverA/src/main/java/promotion/serverA/controller/AdminController.java`
- 변경 내용:
  - GoodsResponse DTO(id, name, stock) 추가. GoodsService에 `findById(Long id)` 추가. GoodsController에 `GET /api/v1/goods/{goodsId}` 엔드포인트 추가.
  - DltResponse DTO(id, orderId, goodsId, quantity, reason, status) 추가. DeadLetterRepository에 `Optional<DeadLetter> findByOrderId(String orderId)` 추가. AdminController에 `GET /api/v1/admin/dlt/orders/{orderId}` 엔드포인트 추가.
- 검증: `.\gradlew.bat :serverA:compileJava`
- 롤백: 6개 파일 변경 취소
- 예상 소요: 짧음

### 단계 2: serverC PaymentCancelConsumer 취소 후 status CANCELLED 업데이트
- 변경 파일:
  - `serverC/src/main/java/promotion/serverC/kafka/PaymentCancelConsumer.java`
- 변경 내용: `pgClient.cancelPayments()` 호출 후 `paymentRepository.updateOrderStatus(orderId, "CANCELLED")` 추가. MockPgClient가 DB status를 변경하지 않는 버그 수정.
- 검증: `.\gradlew.bat :serverC:compileJava`
- 롤백: PaymentCancelConsumer 변경 취소
- 예상 소요: 짧음

### 단계 3: cs-bot DTO 추가 및 CsBotClient 확장
- 변경 파일:
  - `cs-bot/src/main/java/csbot/csbot/client/dto/GoodsResponse.java` (신규)
  - `cs-bot/src/main/java/csbot/csbot/client/dto/OrderStatusResponse.java` (신규)
  - `cs-bot/src/main/java/csbot/csbot/client/dto/DltResponse.java` (신규)
  - `cs-bot/src/main/java/csbot/csbot/client/CsBotClient.java`
- 변경 내용: GoodsResponse(id, name, stock), OrderStatusResponse(orderId, status), DltResponse(id, orderId, goodsId, quantity, reason, status) DTO 추가. CsBotClient에 serverA `getGoodsInfo`, `getDltByOrderId`, `retryDlt` 메서드 + serverB `getOrderStatus` 호출 메서드 추가.
- 검증: `.\gradlew.bat :cs-bot:compileJava`
- 롤백: 4개 파일 변경 취소
- 예상 소요: 짧음

### 단계 4: CsBotTools 도구 4개 추가 + SYSTEM_PROMPT 업데이트
- 변경 파일:
  - `cs-bot/src/main/java/csbot/csbot/tools/CsBotTools.java`
  - `cs-bot/src/main/java/csbot/csbot/router/CsChatAgentService.java` (SYSTEM_PROMPT)
- 변경 내용:
  - `getOrderDetail(orderId)`: serverC 결제정보 + serverA 상품명 + serverB 주문상태 통합
  - `trackRefundStatus(orderId)`: serverC status 재조회 (CANCELLED=완료, PAID=처리 중)
  - `diagnosePaymentIssue()`: PAID인데 serverB 주문상태 불일치 감지 / 동일 goodsId 10분 이내 2건 중복 감지 후 취소 제안
  - `checkAndRetryPurchaseDlt(orderId)`: serverA DLT 조회 → UNRESOLVED이면 retryDlt() 호출(재고 복구) 후 결과 반환. 없거나 RESOLVED이면 "정상 처리됨" 반환.
  - SYSTEM_PROMPT 추가: "취소 요청 후 환불이 안 됐다" → trackRefundStatus → PAID이면 requestRefund 재호출로 재처리 안내
- 검증: `.\gradlew.bat :cs-bot:compileJava`
- 롤백: 2개 파일 변경 취소
- 예상 소요: 보통

### 단계 5: 테스트 코드 추가
- 변경 파일:
  - `cs-bot/src/test/java/csbot/csbot/tools/CsBotToolsTest.java`
  - `serverA/src/test/java/promotion/serverA/controller/GoodsControllerTest.java` (신규)
- 변경 내용: 각 도구 정상/예외 케이스 단위 테스트
- 검증: `.\gradlew.bat :cs-bot:compileTestJava :serverA:compileTestJava`
- 롤백: 테스트 파일 삭제
- 예상 소요: 보통

## 리스크 및 대응
- serverB가 Redis에서 주문 상태 조회 → Redis에 데이터 없으면 "NOT_FOUND" 반환 → 불일치 판단 제외
- serverA, serverB가 로컬 E2E 시 Eureka에 등록되어 있어야 함 → `docker compose up -d server-a server-b redis-b` 추가 기동 필요

## 의존성
- serverA Eureka 이름: `serverA`, serverB: `serverB`
- serverA 의존: redis, server-b (docker-compose)

---

- 작성일: 2026-06-15
- 관련 이슈/티켓: 없음

## 목표
로그인한 고객이 본인의 구매/결제/환불 내역을 조회하고 환불·취소를 요청할 수 있으며, 챗봇이 처리할 수 없는 문의는 Linear 이슈로 에스컬레이션하는 CS 챗봇 백엔드(`cs-bot` 신규 모듈)를 구축한다. 프론트엔드 채팅 위젯은 Phase2로 분리한다.

## 성공 기준
- [ ] `.\gradlew.bat :cs-bot:build` 전체 통과 (단위 테스트 포함)
- [ ] `.\gradlew.bat :user-service:test` 통과 (`InternalUserResponse`에 `id` 추가 후)
- [ ] gateway에 `/api/v1/cs-chat/**` 라우트가 등록되고, `JwtAuthFilter`가 적용되어 JWT 없이 호출 시 401 응답
- [ ] docker-compose로 cs-bot 기동 후: 로그인 → JWT 획득 → `/api/v1/cs-chat/messages` 호출 시 본인의 구매/결제 내역이 정상 반환되는 E2E 확인
- [ ] `requestRefund` 호출 시 `payment-cancel` 토픽에 메시지가 produce되고, serverC `PaymentCancelConsumer`가 이를 소비하는지 로그로 확인

## 비범위 (Out of Scope)
- 프론트엔드 채팅 위젯 (Phase2)
- 실제 PG사 연동 (MockPgClient 유지)
- 환불 금액/이력 기반 리스크 분기, 어드민 검토 큐
- serverB 주문상태(`order:view:{orderId}:status`) read-model 연동 — `PaymentResponse.status`로 대체
- 다국어 지원, 응답 스트리밍
- IntentClassifier/RouterService 같은 멀티 에이전트 라우팅 (CS 도메인은 단일 에이전트로 충분)
- aiops 모듈 코드 변경 (패턴 참고만 함)

## 단계별 작업 계획 (최대 7단계)

### 단계 1: cs-bot 모듈 골격
- 변경 파일:
  - `settings.gradle`
  - `cs-bot/build.gradle`
  - `cs-bot/src/main/java/csbot/csbot/CsBotApplication.java`
  - `cs-bot/src/main/resources/application.yaml`
- 변경 내용 요약: aiops와 동일한 구조(Java 21, Spring Boot 3.5.14, spring-ai-bom 1.1.7)로 신규 모듈 생성. 의존성: Eureka client, web, validation, `spring-ai-starter-model-google-genai`, Lombok, actuator+micrometer(prometheus/otlp, 기존 관측 스택과 일관성), **+ `spring-kafka`(신규, 환불 ACTION에서 `payment-cancel` 토픽 produce용)**. 포트 8089, `spring.application.name: cs-bot`, eureka 등록, `spring.ai.google.genai.api-key: ${AI_API_KEY}`, `spring.kafka.bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`(producer만, consumer 불필요).
- 검증 방법: `.\gradlew.bat :cs-bot:compileJava` → BUILD SUCCESSFUL
- 롤백 방법: `cs-bot/` 디렉토리 삭제, `settings.gradle`에서 `'cs-bot'` 제거
- 예상 소요: 보통

### 단계 2: user-service — Identity 매핑 (`InternalUserResponse`에 `id` 추가)
- 변경 파일:
  - `user-service/src/main/java/com/example/user_service/dto/response/InternalUserResponse.java`
  - (있다면) 관련 테스트 — `UserService.getUserByUserId` 또는 `InternalUserController` 테스트
- 변경 내용 요약: `InternalUserResponse` record에 `Long id` 필드를 추가하고 `fromEntity(User user)`에서 `user.getId()`를 함께 전달한다. cs-bot이 `GET /internal/api/users/{loginId}`(String 로그인ID)를 호출했을 때 숫자 PK(`User.id`)를 받을 수 있게 하기 위함 — 이 값으로 serverC `payments/users/{id}`, user-service `api/users/{id}`를 호출한다. 기존 응답 필드(`userId`, `username`)는 변경하지 않는다(추가 전용, 하위호환).
- 검증 방법: `.\gradlew.bat :user-service:test`
- 롤백 방법: `git checkout -- user-service/src/main/java/com/example/user_service/dto/response/InternalUserResponse.java`
- 예상 소요: 짧음

### 단계 3: cs-bot 인프라 클라이언트 (RestClientConfig, CsBotClient, CsUserContext)
- 변경 파일:
  - `cs-bot/src/main/java/csbot/csbot/config/RestClientConfig.java`
  - `cs-bot/src/main/java/csbot/csbot/client/CsBotClient.java`
  - `cs-bot/src/main/java/csbot/csbot/context/CsUserContext.java`
  - `cs-bot/src/test/java/csbot/csbot/client/CsBotClientTest.java`
- 변경 내용 요약:
  - `RestClientConfig`: aiops `internalServiceClientBuilder()`(DiscoveryClient + Eureka, 3s/60s 타임아웃) 패턴 재사용.
  - `CsBotClient`: ① `resolveNumericUserId(String loginId)` → user-service `GET /internal/api/users/{loginId}` → `InternalUserResponse.id` 반환, ② `getMyPayments(Long numericUserId)` → serverC `GET /api/v1/payments/users/{numericUserId}` → `List<PaymentResponse>`, ③ `getMyProfile(Long numericUserId, String loginId)` → user-service `GET /api/users/{numericUserId}`(`X-User-Id: loginId` 헤더 포함) → `UserProfileResponse`.
  - `CsUserContext`: `@RequestScope` 빈. `X-User-Id`(String 로그인ID)를 보관하고, `resolveNumericId()`에서 `CsBotClient.resolveNumericUserId`를 호출해 결과를 요청 내에서 캐싱한다. 모든 `@Tool`은 이 컨텍스트를 통해서만 사용자 식별자를 얻는다 — LLM이 생성한 userId를 절대 신뢰하지 않는다는 보안 원칙의 구현체.
- 검증 방법: `.\gradlew.bat :cs-bot:test --tests "*CsBotClientTest"`
- 롤백 방법: 3개 신규 파일 삭제
- 예상 소요: 보통

### 단계 4: CS 도구(@Tool) — 조회 통합 도구 + 환불/취소 ACTION + 에스컬레이션
- 변경 파일:
  - `cs-bot/src/main/java/csbot/csbot/tools/CsBotTools.java`
  - `cs-bot/src/main/java/csbot/csbot/linear/CsEscalationService.java`
  - `cs-bot/src/test/java/csbot/csbot/tools/CsBotToolsTest.java`
- 변경 내용 요약:
  - `getMyOrders()`: `CsUserContext.resolveNumericId()` → `CsBotClient.getMyPayments(id)` → `PaymentResponse` 리스트 반환. 구매/주문 내역, 결제 내역/실패 사유, 환불/취소 상태 조회를 이 단일 도구로 통합(`status` 필드로 모두 표현 가능).
  - `getMyProfile()`: `CsBotClient.getMyProfile(id, loginId)` → 회원 정보 반환.
  - `requestRefund(orderId)`: `getMyOrders()` 결과에서 해당 `orderId`가 본인 소유이고 `status`가 취소 가능한 상태(`SUCCESS`)인지 확인(상태 가드/멱등성) → 통과 시 `KafkaTemplate`으로 `payment-cancel` 토픽에 `PaymentCancelMessage(orderId)`(JSON) produce → "취소 요청이 접수되었습니다" 응답. 본인 주문이 아니거나 이미 취소/실패 상태면 처리 거부 사유를 반환(별도 승인 절차 없음 — 즉시 처리).
  - `escalateToHuman(summary)`: `CsEscalationService.createEscalationTicket(summary, conversationId)` 호출 — aiops `LinearAuditService`와 동일한 GraphQL `issueCreate` mutation 패턴을 독립 구현(aiops 모듈 의존 없음), title prefix `[CS 에스컬레이션]`. 실패 시 예외 throw 없이 안내 문자열 반환.
  - 모든 `@Tool`은 `@ToolParam`으로 사용자 식별자를 받지 않는다.
- 검증 방법: `.\gradlew.bat :cs-bot:test --tests "*CsBotToolsTest"`
- 롤백 방법: 3개 파일 삭제
- 예상 소요: 김

### 단계 5: Router/Agent + Controller
- 변경 파일:
  - `cs-bot/src/main/java/csbot/csbot/router/ChatMemoryConfig.java`
  - `cs-bot/src/main/java/csbot/csbot/router/CsChatAgentService.java`
  - `cs-bot/src/main/java/csbot/csbot/controller/CsChatController.java`
  - `cs-bot/src/test/java/csbot/csbot/controller/CsChatControllerTest.java`
- 변경 내용 요약:
  - `ChatMemoryConfig`: aiops와 동일 — `MessageWindowChatMemory` + `InMemoryChatMemoryRepository`(maxMessages=20). replica=1 가정(`docs/context.md`에 명시).
  - `CsChatAgentService`: SYSTEM_PROMPT(CS 응대 톤, "본인 데이터만 다룬다"는 안내, `requestRefund` 호출 전 `getMyOrders`로 본인 주문 확인 지시, 해결 불가 시 `escalateToHuman` 안내) + `ChatClient.Builder` + `ChatMemory` + `CsBotTools`를 `chat(conversationId, message)`로 노출 (InfraChatAgentService와 동일 구조).
  - `CsChatController`: `POST /api/v1/cs-chat/messages` — `X-User-Id` 헤더(필수)를 `CsUserContext`에 설정한 뒤 `CsChatAgentService.chat(...)` 호출, 응답 반환. 요청 바디: `{conversationId, message}`.
- 검증 방법: `.\gradlew.bat :cs-bot:test --tests "*CsChatControllerTest"`
- 롤백 방법: 4개 파일 삭제
- 예상 소요: 보통

### 단계 6: gateway 라우팅 추가 (인프라 변경 — 단계별 승인 유지)
- 변경 파일: `gateway-service/src/main/resources/application.yml`
- 변경 내용 요약: `/api/v1/cs-chat/**` → `lb://cs-bot` 라우트 추가. 기존 인증 필요 라우트(`user-service`/`api/users/**` 등)와 동일한 Redis rate-limit 패턴 적용. `JwtAuthFilter`는 전역 필터이므로 별도 설정 없이 `X-User-Id`/`X-User-Role` 주입이 자동 적용된다.
- 검증 방법: `.\gradlew.bat :gateway-service:compileJava` + gateway 기동 후 라우트 등록 확인
- 롤백 방법: `git checkout -- gateway-service/src/main/resources/application.yml`
- 예상 소요: 짧음

### 단계 7: docker-compose 통합 + 전체 빌드/E2E 검증 (인프라 변경 — 단계별 승인 유지)
- 변경 파일: `docker-compose.yml`
- 변경 내용 요약: `cs-bot` 서비스 추가(포트 8089). aiops와 동일한 환경변수 패턴(`AI_API_KEY`, `EUREKA_DEFAULT_ZONE`, `LINEAR_API_KEY`, `LINEAR_TEAM_ID`, `OTLP_ENDPOINT` 등) + `SPRING_KAFKA_BOOTSTRAP_SERVERS`. `depends_on: discovery-service, kafka`.
- 검증 방법: `.\gradlew.bat :cs-bot:build`(전체) → `docker compose config` → `docker compose up -d cs-bot`(run_in_background) → 기동 후 curl로 로그인→JWT 획득→gateway 경유 `/api/v1/cs-chat/messages` 호출 E2E, `requestRefund` 호출 시 serverC 로그에서 `PaymentCancelConsumer` 소비 확인
- 롤백 방법: `git checkout -- docker-compose.yml`, `docker compose down cs-bot`
- 예상 소요: 김

## 리스크 및 대응
- 리스크 1: `spring-kafka`는 cs-bot에 추가되는 **신규 Gradle 의존성** → CLAUDE.md 규칙상 사용자 확인 필요(이 plan 승인 시 함께 확인).
- 리스크 2: `CsUserContext`(`@RequestScope`)에 담긴 `X-User-Id`를 `@Tool` 메서드 실행 시점에 정상적으로 읽을 수 있는지 — Spring AI `ChatClient.call()`은 기본적으로 호출 스레드에서 동기 실행되므로 동일 요청 스레드 내에서는 문제 없으나, virtual thread 전환 시 `RequestContextHolder` 전파 필요 여부를 단계 3/5 구현 중 확인한다.
- 리스크 3: `requestRefund`의 상태 가드 기준(`SUCCESS`만 취소 가능)이 `Payment.status`의 실제 값 집합과 일치하는지 — 단계 4 구현 시 `PgClient`/`Payment` 엔티티의 status 값들을 확인해 가드 조건을 정확히 맞춘다.
- 리스크 4: `LINEAR_API_KEY`/`LINEAR_TEAM_ID`가 로컬에 없을 수 있음 → `CsEscalationService`는 `LinearAuditService`처럼 실패 시 예외 대신 안내 문자열을 반환한다.

## 의존성
- ⚠️ 신규 Gradle 의존성: `cs-bot` → `spring-kafka` (producer only)
- 기존 `payment-cancel` Kafka 토픽 (serverA `KafkaTopicConfig`에서 선언, 3 partitions/1 replica) — cs-bot은 새로 선언하지 않고 produce만 함
- 기존 `GET /internal/api/users/{userId}` (user-service, gateway 미노출, 서비스간 전용) + 본 plan에서 `id` 필드 추가
- 기존 `GET /api/v1/payments/users/{userId}` (serverC, `PaymentResponse`)
- 기존 `GET /api/users/{id}` (user-service, `UserProfileResponse`, `X-User-Id` 헤더로 본인 검증)
- aiops `CodebotClient`/`RestClientConfig.internalServiceClientBuilder`, `ChatMemoryConfig`, `InfraChatAgentService`, `LinearAuditService` — 패턴 참고용 템플릿(코드 재사용 아닌 패턴 복제)
