# Design Notes
> 코드만 봐서는 알 수 없는 설계 결정과 제약을 기록한다.
> 구현 완료 후 비자명한 결정이 있을 때만 추가한다. 자동 생성 금지.

---

## 재고 동시성 제어 (3단계 방어)

**결정:** DB 레벨 락 없이 Redis 3단계로 동시성 제어

**1단계 — Caffeine 로컬 캐시 (품절 캐시)**
- `RedisStockService.isKnownSoldOut()`: Redis 왕복 없이 메모리에서 즉시 품절 차단
- 만료: 60초. 재고가 복구돼도 최대 60초 동안 차단 유지 (의도된 트레이드오프)

**2단계 — Redis SETNX (중복 구매 방어)**
- 키: `user:purchase:{userId}:{goodsId}`, TTL 1시간
- `setIfAbsent`로 동일 유저의 동일 상품 재구매 원천 차단
- Kafka 발행 실패 시 `releaseUserPurchase()`로 즉시 삭제

**3단계 — Redis Lua 스크립트 (재고 선점)**
- 키: `goods:stock:{goodsId}`, TTL 없음
- Lua 스크립트로 `GET → 비교 → DECRBY`를 원자적으로 실행
  - 재고 충분: DECRBY 후 남은 재고 반환
  - 재고 부족: `-1` 반환 (DECRBY 실행 안 함)
  - 키 없음: `-2` 반환
- 음수 반환 시 Lua 내부에서 이미 차감이 발생하지 않으므로 별도 롤백 불필요
- Kafka 발행 실패 시에만 `releaseStock()`(INCRBY)으로 수동 복구

**채택하지 않은 대안:**
- DB 비관적 락: 커넥션 풀 고갈 위험
- DB 낙관적 락(`@Version`): 충돌 시 재시도 폭탄

---

## 구매 요청 흐름: Outbox 미사용 구간

**`purchase_events` 토픽은 Outbox 패턴을 사용하지 않는다.**

`PromotionService.acceptPurchase()`에서 `kafkaTemplate.send().get(3, TimeUnit.SECONDS)`로 동기 발행한다.
- 3초 타임아웃 초과 시 Redis 재고·중복구매 플래그 즉시 롤백 후 클라이언트에 에러 반환
- `stock-snapshot` (구매 접수 시점) 도 직접 발행, 실패해도 무시 (fire-and-forget)

**이유:**
- 구매 요청 단계는 아직 DB Order가 없으므로 Outbox 트랜잭션을 묶을 대상이 없음
- 대신 Redis 선점 후 Kafka 발행 실패 시 Redis 롤백으로 정합성 유지

**Outbox를 사용하는 구간 (DB 트랜잭션과 묶임):**
- `OrderCommandService`: `order-status-update`(PENDING), `payment-request`
- `SagaOrchestratorService`: `order-status-update`(PAID/FAILED/EXPIRED), `order-completed`, `payment-cancel`, `stock-snapshot`(saga 결과)

---

## Saga 패턴: Orchestration 선택

**결정:** Choreography 대신 Orchestration 채택, serverA가 단독 제어

**전체 흐름:**
```
[Client] → PromotionService → purchase_events (직접 발행, 동기 3s)
         ↓
PurchaseKafkaConsumer → OrderCommandService
  - DB: Order(PENDING) 저장, Goods 재고 차감
  - Redis: SagaState 초기화
  - Outbox: payment-request, order-status-update(PENDING)
         ↓ (병렬)
  serverC ← payment-request → payment-result → serverA/SagaResultConsumer
  serverB ← order-status-update → status-update-result → serverA/SagaResultConsumer
         ↓ (둘 다 성공 시)
SagaOrchestratorService.tryCompleteSaga()
  - DB: Order → PAID (updateStatusIfPending, 멱등성)
  - Outbox: order-status-update(PAID), order-completed
         ↓ (실패 시)
SagaOrchestratorService.handleSagaFailure()
  - DB: Order → FAILED / EXPIRED
  - Redis: 재고·유저마킹 복구
  - DB: Goods 재고 복구 (increaseStockAtomically)
  - Outbox: payment-cancel (결제가 성공했던 경우만), stock-snapshot, order-status-update(FAILED)
```

**멱등성 보장:**
- `orderRepository.updateStatusIfPending()`: PENDING 상태일 때만 변경, 중복 실행 시 0 반환
- `sagaStateService.markFailedAndCheck()`: 이미 실패 처리된 Saga는 무시

---

## Saga 타임아웃

**결정:** `SagaTimeoutScheduler`가 30초마다 Redis SCAN으로 미완료 Saga 탐지

- 키 패턴: `saga:state:*`
- 타임아웃 기준: 생성 후 **3분** 초과
- 결과: `handleSagaFailure(orderId, "TIMEOUT")` → Order 상태 **EXPIRED** (FAILED와 구분)
- 이유: 결제 응답이 없는 Saga를 무한 대기하면 Redis 상태 영구 잠김

**결정 (추가): EXPIRED 전에 serverC 결제를 조회하고, 결과를 모르면(UNKNOWN) 판단을 미룬다. 실패 확정 뒤 늦게 온 결제 성공은 망취소로 보상한다.**

- 조회 결과 `PAID` → PAID 보정, `NOT_PAID` → EXPIRED, `UNKNOWN`(serverC 미발견·조회 실패) → 건너뛰고 다음 주기 재조회.
- `SagaResultConsumer`는 Saga 상태가 이미 없으면 플래그를 기록하지 않는다. `payment-result(success)`면 `compensateLatePaymentSuccess` — 주문이 EXPIRED/FAILED면 `payment-cancel` 발행.

**이유:**
- `handleSagaFailure`의 망취소 여부는 **그 시점 Saga 플래그(`paymentCompleted`)**로만 정해진다. 결과 이벤트가 아직 안 왔는데 UNKNOWN을 EXPIRED로 처리하면, serverC에서 실제 승인된 결제가 망취소 없이 남는다 (로컬 재현: serverC PAID 30,000원 + 주문 EXPIRED).
- 실패 확정 뒤 결제 성공이 늦게 오면 기존 코드는 삭제된 키에 HSET해 `createdAt` 없는 **좀비 키**를 만들고(타임아웃 스캐너가 영원히 스킵) 망취소도 하지 않았다. 늦은 성공은 UNKNOWN 이후·처리 중 타임아웃·DB 재대사 실패 등 여러 경로에서 생기므로 결과 수신 지점 한 곳에서 막는다.

**제약/불변식:**
- serverC 장애가 길면 주문이 PENDING으로 남고 재고가 묶인다(`saga.timeout.payment_unknown`으로 관측). 최대 대기·알람은 미구현.
- 결과 이벤트가 **영영 오지 않는** 경우(CDC 유실)는 이벤트 기반으로는 막지 못한다 → BL-003에서 `OrderReconciliationScheduler.reconcileTerminatedOrders`(종료 주문을 serverC에 재조회해 PAID면 망취소)로 보완.

**결정 (BL-003): 결과 유실 재대사는 serverA가 종료 주문(EXPIRED/FAILED)을 생성 후 10분~24시간 동안 주기적으로 serverC에 재조회한다.**
- 하한 10분: 정상적인 늦은 결과(`compensateLatePaymentSuccess`)가 먼저 처리되게 해 중복 망취소를 줄인다. 상한 24시간: 그 이상의 CDC 장애는 장애 대응 영역.
- 망취소도 Outbox→CDC를 거친다. CDC가 멈춰 있으면 주기마다 `payment-cancel`이 쌓였다가 복구 시 중복 발행된다 — serverC CANCELLED 덮어쓰기(모의 PG 멱등)에 의존, 실제 PG는 BL-005.
- 재대사 망취소는 `saga.reconcile_cancel.total`로 별도 집계 — 0이 아니면 CDC 유실이 있었다는 신호.
- 채택하지 않은 대안: serverC 측 재대사(serverC가 PAID 결제를 스캔해 serverA에 주문 상태 조회 후 직접 망취소) — CDC를 우회하지만 serverA 조회 API·serverC 클라이언트가 새로 필요하고, PAID 결제는 대다수라 스캔 대상이 많다. serverA 방식도 serverC가 CANCELLED가 될 때까지 다음 주기에 다시 발행하므로 결국 복구된다.
- 늦은 성공이 중복 수신되면 `payment-cancel`이 두 번 나갈 수 있다. serverC 취소는 상태 무관 CANCELLED 덮어쓰기라 모의 PG에서는 멱등이지만, 실제 PG의 중복 취소 응답 처리는 미검증.

**채택하지 않은 대안:**
- UNKNOWN이면 EXPIRED + 무조건 망취소 → 결제가 아직 처리 전이면 취소가 먼저 도착하고 결제가 나중에 승인되는 경쟁 발생(serverC 수정 필요).
- Saga 플래그 기록을 Lua로 원자화(키 있을 때만 HSET) → `exists` 확인과 기록 사이 경쟁 창이 매우 짧고 결과가 기존 동작과 같아 과설계로 판단.

---

## Outbox 패턴 + Debezium CDC

**결정:** Saga 내부 메시지는 `kafkaTemplate.send()` 직접 호출 대신 Outbox 테이블 → Debezium CDC

**이유:**
- `OrderCommandService`, `SagaOrchestratorService`의 메시지 발행은 DB 트랜잭션(Order 저장, 재고 차감)과 원자적으로 묶여야 함
- 직접 발행은 DB 커밋 후 Kafka 발행 실패 시 메시지 유실 위험
- Outbox 테이블 INSERT → DB 트랜잭션 성공 → Debezium CDC 감지 → Kafka 발행

**traceparent 전파:**
- `OutboxEventService.save()`가 MDC에서 현재 traceId/spanId를 읽어 W3C traceparent 형식으로 조립
- Debezium이 `outbox_event.traceparent` 컬럼 값을 Kafka 메시지 헤더로 주입
- serverC Consumer들이 헤더에서 추출해 Child Span 생성 → 단일 분산 트레이스 연결

**OutboxEventService 제약:**
- 반드시 호출자의 `@Transactional` 범위 안에서 호출해야 함 (주석으로 명시됨)
- 트랜잭션 밖에서 호출 시 메시지 유실 가능

**사용 모듈:** serverA, serverC

---

## CQRS: serverB 읽기 전용 분리

**결정:** 주문 상태·재고 조회를 serverA가 아닌 serverB(DB 없음, Redis만 사용)에서 처리

**이유:**
- 플래시세일 중 쓰기(serverA)와 읽기가 동일 서버에서 경합하면 쓰기 성능 저하
- serverB는 Redis 뷰 조회만 하므로 응답이 빠르고 serverA에 부하를 주지 않음

**트레이드오프:**
- 최종 일관성 모델 — 주문 직후 상태 조회 시 수십 밀리초 지연 가능
- 의도된 설계이며 버그가 아님

---

## DLT 처리 전략

**결정:** 재시도 소진 메시지를 DB(`dead_letter` 테이블)에 저장, 관리자 수동 재처리

**흐름:**
- `purchase_events.DLT` → `PurchaseDltConsumer` → `dead_letter` 테이블 저장
- 관리자 → `POST /api/v1/admin/dlt/{dltId}/retry` → 수동 재처리
- `payment-cancel.DLT` → 카운터 증가 + 로그 (`business_payment_pg_refund_fatal_total`)
  - 자동 재처리 없음, 수동 정산 필요

**이유:** 재시도를 모두 소진한 메시지는 시스템 오류가 아닌 데이터·비즈니스 이상일 가능성이 높아 사람이 확인 후 판단해야 함

---

## codebot PR 자동화: 결정적 브랜치명 (Find-or-Create)

**결정:** `PullRequestTools.createFixPullRequest`의 브랜치명은 `feature/{issueIdentifier 소문자}-codebot-fix`로 고정 (예: `feature/mic-12-codebot-fix`). LLM이 자유롭게 브랜치명을 생성하지 않는다.

**동작:**
- `GET /git/refs/heads/{branch}`로 브랜치 존재 여부 확인 → 없으면 main 기반으로 신규 생성 + PR 생성, 있으면 같은 브랜치에 Contents API로 추가 커밋 후 `GET /pulls?head=...&state=open`으로 기존 PR을 찾아 반환.
- 같은 이슈에 대해 "고쳐서 PR 올려줘"를 여러 번 호출해도 새 브랜치/PR이 늘어나지 않고 같은 브랜치/PR에 커밋이 누적된다.

**제약/불변식:**
- 동일 `issueIdentifier`는 항상 동일 브랜치를 가리킨다 — ChatMemory에 "이전에 만든 PR" 상태를 별도로 저장하지 않는다 (GitHub이 source of truth).
- 브랜치는 있지만 open PR이 없는 경우(이전 PR이 머지/닫힘)는 안내 메시지만 반환하고 새 브랜치를 만들지 않는다 — "처음부터 다시" 시나리오는 비범위.

**채택하지 않은 대안:**
- 타임스탬프/해시 슬러그로 매번 새 브랜치/PR 생성 → 재요청마다 PR이 누적되어 레포 관리 비용 증가
- git clone/checkout 기반 진짜 상태관리(V2) → 단일 파일 + 명시적 트리거 범위에서는 과한 복잡도

---

## Istio 카나리(v1/v2) 격리: Service selector를 `istio-canary-group` 라벨로 전환

**결정:** server-a/b/c의 `spec.selector`(Deployment, immutable)는 그대로 두고, 대신 (1) v1 pod template에 신규 라벨 `istio-canary-group: server-X`를 추가하고 (2) Service의 `spec.selector`를 `app: server-X` → `istio-canary-group: server-X`로 전환했다. v2(canary) Deployment는 `selector.matchLabels: {app: server-X-canary}`로 v1과 겹치지 않게 분리하고, pod 라벨에 `version: v2`, `istio-canary-group: server-X`를 부여해 동일 Service의 엔드포인트로 합류시킨다.

**이유:**
- K8s Deployment의 `spec.selector`는 immutable이라 v1 selector를 `{app: server-X, version: v1}`처럼 표준 카나리 패턴으로 직접 바꾸면 기존 클러스터에서 `helm upgrade`가 "field is immutable" 에러로 실패하고 재생성 시 다운타임이 발생한다.
- `istio-canary-group` 공통 라벨 + Service selector 전환 방식은 v1 Deployment의 selector를 건드리지 않으면서, Service가 v1/v2 파드를 모두 엔드포인트로 포함하게 만든다. DestinationRule의 v1/v2 subset(`version` 라벨 기준)은 이미 존재하므로 변경 불필요.

**EKS 적용 순서 (무중단 전제):**
1. v1 Deployment에 `istio-canary-group` 라벨 추가 → rollout 완료 대기 (모든 v1 파드가 새 라벨을 가짐)
2. Service의 `spec.selector`를 `istio-canary-group: server-X`로 전환 (Service selector는 즉시 적용, mutable)
3. (선택) `serverX.canary.enabled=true`로 v2 Deployment 배포

1→2 순서를 지키지 않으면, 라벨이 아직 없는 v1 파드가 일시적으로 Service 엔드포인트에서 빠질 수 있다.

**제약/불변식:**
- Prometheus `istio-waypoint` job은 waypoint pod 라벨이 `gateway.networking.k8s.io/gateway-name: waypoint`이고 15020 포트에서 `/stats/prometheus`를 노출한다고 가정한다 — EKS 배포 후 Prometheus Targets 페이지에서 실제 라벨/포트를 확인 필요.
- v2(canary) Deployment는 고정 replica이며 HPA를 적용하지 않는다.

**채택하지 않은 대안:**
- v1 Deployment selector를 `{app: server-X, version: v1}`로 직접 변경(표준 카나리 패턴) → `spec.selector` immutable로 인해 무중단 적용 불가.
- Micrometer common-tags로 앱 메트릭에 `version` 라벨 추가 → 앱 재배포 필요, Istio Ambient(앱 코드 비수정)의 취지와 불일치. waypoint Envoy 메트릭(`istio_requests_total{destination_version=...}`)으로 동일 정보를 코드 변경 없이 획득 가능.

## 카나리(v2) 점진적 자동 승급: 스케줄러와 알람의 역할 분리

**결정:** "비정상 시 즉시 격리"는 `CanaryV2ErrorRateHigh` 알람 → `AiOpsAgentService.analyze()` → 시나리오10의 `proposeTrafficShift(v1=100,v2=0)` 경로로, "정상 지속 시 단계적 승급"은 신규 `CanaryRolloutScheduler`(`@Scheduled`, 기본 5분 주기)로 역할을 분리했다. 두 경로 모두 최종 트래픽 변경은 기존 `proposeTrafficShift` Slack 승인을 그대로 통과한다.

**이유:**
- 기존 `analyze()`는 Alertmanager의 firing 알람으로만 트리거되는데, "v2가 계속 정상"이라는 사실 자체는 알람화하기 어렵다(한 번도 firing하지 않으면 resolved 알림도 발생하지 않음) — 그래서 점진 승급만 별도 스케줄러로 분리했다.
- `CanaryRolloutScheduler`에 자체 롤백(에러율 급증 시 v2=0 격리) 로직을 추가하면 `CanaryV2ErrorRateHigh` → `analyze()` 경로와 동일 상황에서 Slack에 중복 제안이 발생한다. 그래서 "비정상 시 격리"는 알람 경로 하나로만 처리하고, 스케줄러는 에러율 초과 시 연속 정상 카운터만 리셋한다(별도 조치 없음).

**연속 정상 카운터(시간 기반 대신) 채택:**
- "v2가 N분간 정상"을 `Instant`/`Duration`으로 추적하는 대신 "스케줄러 틱마다 연속 정상 횟수"로 구현했다. 스케줄러 interval × `healthy-checks-required`가 사실상 동일한 시간 의미를 가지면서, 단위 테스트에서 시간 모킹 없이 `checkService()`를 N번 호출하는 것만으로 검증 가능하다.

**제약/불변식:**
- `CanaryRolloutScheduler`의 PromQL은 `istio_requests_total{destination_service_name="<service>", destination_version="v2"}` 라벨 조합을 사용한다. `destination_service_name`의 실제 값(예: `server-a` vs `server-a.promotion.svc.cluster.local`)은 waypoint 메트릭 배포 후 EKS Prometheus에서 직접 확인이 필요하다 — 실제 값이 다르면 `canary.rollout.services`를 그 형식에 맞게 설정해야 한다.
- `getCanaryWeight`가 -1(조회 실패)을 반환하는 경우도 weight 0/100과 동일하게 "스킵 + 상태 초기화"로 처리한다. 일시적 kubectl 실패 시 진행 카운터가 리셋될 수 있지만, 다음 정상 틱부터 다시 누적되므로 별도 재시도/구분 로직 없이 단순성을 우선했다.

---

## K8s 서비스 간 호출: DiscoveryClient + SimpleDiscoveryClient 정적 등록

**결정:** `DiscoveryClient`로 다른 서비스를 찾는 클라이언트(serverA `PaymentQueryClient`, cs-bot `CsBotClient`, aiops `CodebotClient`)는 코드를 그대로 두고, 각 모듈의 `application-k8s.yml`에 `spring.cloud.discovery.client.simple.instances.<serviceId>`로 K8s Service DNS(`http://<helm name>:<port>`)를 정적 등록한다.

**이유:**
- K8s에는 discovery-service(Eureka)를 배포하지 않는다. 등록이 없으면 `getInstances()`가 빈 리스트를 반환해 **예외 없이 조용히 실패**한다 (serverA는 결제조회 UNKNOWN → 결제 성공 주문도 EXPIRED, cs-bot은 조회 Tool 전부 실패, aiops는 codebot 라우팅 불가).
- `SimpleDiscoveryClient`는 spring-cloud-commons 기본 구성이라 새 의존성·Java 변경 없이 yml만으로 해결되고, local(Eureka) 동작은 그대로다. K8s Service가 이미 파드 로드밸런싱을 하므로 동적 조회 이득이 없다.

**제약/불변식:**
- 새 모듈이나 새 호출 대상에서 `DiscoveryClient`를 쓰면 그 모듈 `application-k8s.yml`에 정적 등록을 함께 추가해야 한다. 키는 코드의 서비스 ID 문자열(`serverC`, `user-service` 등)과 정확히 일치해야 한다.
- 바인딩은 각 모듈의 `config/K8sDiscoveryConfigTest`로 검증한다. 실제 DNS 연결은 로컬 테스트로 검증되지 않는다.
- Helm Service 이름/포트(`helm/promotion-app/values.yaml`)가 바뀌면 이 등록도 함께 바꿔야 한다.

**채택하지 않은 대안:**
- 고정 URL 프로퍼티(`@Value("${...url}")`)로 전환 → Java·테스트·docker-compose 환경변수까지 변경 범위가 커짐.
- spring-cloud-kubernetes discovery → 새 의존성 + ServiceAccount RBAC(endpoints 조회) 추가 필요, Service DNS 대비 이득이 적음.

---

## 결제 금액: 서버 계산 + 주문 저장 단계 스냅샷 + PG 승인 금액 검증

**결정:**
1. 금액은 원화 `long`(원 단위) + DB `BIGINT`, 통화는 `VARCHAR(3)`(`KRW`)로 금액과 함께 저장한다.
2. 클라이언트는 금액을 보내지 않는다. serverA가 **주문 저장 단계**(`OrderCommandService.processNewOrder`, Kafka 소비 후 DB 트랜잭션)에서 DB 가격 × 수량으로 계산해 `Order`에 스냅샷하고, `payment-request`(`PaymentRequestMessage`)에 실어 보낸다.
3. serverC는 PG 승인 금액(`PgTransactionResult.approvedAmount`)이 주문 금액과 다르면 **직접 즉시 망취소**한 뒤 FAILED + `payment-result(false)`로 serverA 보상을 트리거한다.
4. 가격 0원(가격 미설정)·상품 없음은 결제 요청 없이 기존 `handleSagaFailure("INVALID_PRICE")`로 실패 처리한다.

**이유:**
- 구매 접수(`acceptPurchase`)는 DB 없이 Redis만 거치는 TPS 핵심 경로다. 가격 조회를 여기 넣으면 그 설계가 깨지므로, 이미 DB 트랜잭션인 주문 저장 단계에서 조회한다. 프로모션 중 가격은 고정이라 접수~소비 사이 수 초 차이는 허용한다.
- serverA의 `payment-cancel`은 Saga 상태에서 "결제 성공"으로 확인된 건에만 발행된다. 금액 불일치 건은 serverC가 FAILED로 응답하므로 serverA 경로로는 망취소가 나가지 않는다 — 그래서 serverC가 직접 취소해야 고객 돈이 묶이지 않는다.
- 원화는 소수가 없어 정수가 가장 단순하고 오차가 없다. `BigDecimal`은 수수료율 계산이 생기는 정산 단계에서 사용한다.

**제약/불변식 (확장-축소 패턴):**
- `payment-request`는 필드 **추가만** 한다. serverA·serverC 컨슈머는 Spring Boot `ObjectMapper`(모르는 필드 무시)를 쓰므로 옛 serverC는 새 필드를 무시한다. **단, serverB는 `new ObjectMapper()`라 모르는 필드에 실패한다** — serverB가 소비하는 토픽에 필드를 추가할 때는 주의.
- serverC는 금액 없는 옛 메시지를 검증 없이 처리하고 `business_payment_legacy_without_amount_total`을 올린다. 권장 배포 순서는 serverC → serverA. 이 카운터가 운영에서 계속 0이면 다음 단계로 금액 필수화(옛 메시지 거부)를 진행한다 — 현재 미완료.
- `Order.unitPrice/totalAmount/currency`, `payments.amount/currency`는 금액 도입 이전 행 때문에 NULL 허용이다.
- PG가 승인 금액을 돌려주지 않으면(null) 검증 불가로 보고 불일치와 동일하게 망취소한다.

**채택하지 않은 대안:**
- `PurchaseMessage`에 금액 필드를 넣어 두 토픽 공용 → 구매 접수 시점엔 금액을 몰라 `purchase_events`에서 항상 null인 필드가 생김.
- 구매 접수 시점에 가격 조회 → 접수 경로에 캐시/DB 조회 추가로 TPS 설계 변경.
- 금액 불일치 시 FAILED만 기록하고 망취소는 수동 → 고객 돈이 승인된 채 묶임.
- 금액 없는 옛 메시지를 즉시 실패 처리 → 배포 중 들어온 정상 주문이 실패.

---

## E2E 두 층과 도구: 백엔드 E2E(`backend-e2e/`) + UI E2E(`frontend/e2e/`), 둘 다 Playwright

**결정:** 백엔드팀·프론트팀이 분리돼 있다고 가정하고, E2E를 "무엇을 검증하느냐"로 두 층으로 나눠 각 팀이 소유한다.
- 백엔드 E2E: 독립 폴더 `backend-e2e/`. 떠 있는 serverA·B·C에 HTTP로 요청해 구매 → 결제 금액·PAID → 주문 PAID를 확인. 프론트·브라우저 불필요(Playwright `request`만 사용).
- UI E2E: `frontend/e2e/`. 화면 흐름(구매 화면 → 주문 상태 PAID 표시).
- "둘 다 구현됨"일 때 별도의 세 번째 E2E는 없다 — 두 층을 함께 돌린다.

**이유:**
- 백엔드 E2E를 `frontend/` 안에 두면 백엔드 흐름 검증이 프론트 빌드·서버에 묶이고, 팀이 나뉘면 소유권이 맞지 않는다.
- 도구를 Playwright 하나로 통일하면 사전 점검·환경변수·리포트 보는 법이 API·UI에서 같다. HTML 리포트(단계별 응답 첨부)와 trace 뷰어로 결과를 사람이 직접 확인하기 좋고, 새 의존성이 없다. `expect.poll`로 비동기 결과(PAID) 대기가 간단하다.
- 같은 검증을 두 도구(예: Postman 컬렉션 + Playwright)에 중복 자동화하지 않는다 — 원본은 `backend-e2e/` 하나.

**제약/불변식:**
- 백엔드 E2E는 DB를 직접 조회하지 않고 공개 API로만 확인한다(블랙박스). 그래서 serverC 결제 조회 응답에 `amount`·`currency`를 포함한다.
- `backend-e2e/SCENARIOS.md`가 도구와 무관한 시나리오 명세다. 테스트의 단계 이름(①~④)과 번호를 맞춘다.
- 사전 점검은 HTTP(health·Kafka Connect REST)만 쓴다 — 셸·Docker CLI 의존 없음. 백엔드·프론트가 각자 같은 점검을 가진다(팀 소유 분리).

**전환 조건 (REST Assured로 바꿀 때):** 백엔드팀이 Node·TypeScript를 다루지 않기로 하거나, 백엔드 테스트를 Gradle 안에서만 돌리는 것이 표준이 되거나, 서버 DTO와 응답 구조를 컴파일 시점에 맞춰야 할 때. 이때 `SCENARIOS.md`를 기준으로 Gradle 모듈(JUnit 5 + REST Assured + Awaitility)로 다시 만든다. 그 경우에도 UI E2E는 Playwright로 남아 도구가 두 벌이 되는 비용을 감수한다.

**채택하지 않은 대안:**
- Postman 컬렉션 + Newman → 현업에서 흔하고 팀 공유에 좋지만, 비동기 대기를 스크립트로 반복 요청해야 하고 큰 JSON이라 리뷰가 어렵다. Claude가 앱 화면을 조작할 수 없어 결과를 보여주려면 HTML 리포터 의존성이 더 필요. 수동 확인·공유용으로는 별도 검토(백로그).
- REST Assured(Java 모듈) → 백엔드 언어로 소유하기 좋지만, UI E2E와 도구가 두 벌이 되고 결과를 화면으로 보기 불편, 새 의존성 2개.
- 전환 대비 주석 코드 → 실행되지 않는 코드는 금방 낡는다. 시나리오 명세 + 전환 조건 기록으로 대신.

---

## PR CI 필수 체크: 워크플로마다 항상 실행되는 result job

**결정:** `backend-ci`·`frontend-ci`는 모든 PR에서 실행되고, job 안에서 `dorny/paths-filter`로 변경 여부를 판단해 테스트를 건너뛴다. 항상 실행되는 `backend-ci-result`·`frontend-ci-result` 두 job만 main 브랜치 보호의 필수 체크로 지정한다.

**이유:**
- 워크플로 단위 `paths` 필터면 해당 경로가 안 바뀐 PR에서 체크가 아예 생성되지 않고, 필수 체크는 "대기"로 남아 머지가 막힌다. skipped job은 통과로 인정되므로 job 단위로 건너뛴다.
- matrix 9개를 각각 필수로 지정하면 모듈 추가·삭제 때마다 보호 설정을 고쳐야 한다. result 하나로 모은다.

**제약/불변식:**
- result job의 `name`(`backend-ci-result`·`frontend-ci-result`)은 보호 규칙의 체크 이름이다. 바꾸면 보호 설정도 함께 바꿔야 한다(아니면 모든 PR이 대기로 막힘). 두 워크플로에서 이름이 겹치면 안 된다.
- 테스트 job을 추가하면 result의 `needs`에도 넣는다. 빠지면 그 job이 실패해도 머지된다.
- 백엔드 경로 목록은 `backend-ci.yml`의 `push.paths`와 `changes` filters 두 곳에 있다. `'!frontend/**'` 같은 제외 패턴이 GitHub `paths`와 같은 의미가 되도록 `predicate-quantifier: some-with-excludes`를 쓴다.

**채택하지 않은 대안:**
- 경로 필터 제거(항상 전체 실행) → 문서 PR에도 백엔드 9개 job이 돌아 러너 낭비, BL-001의 "문서만 바뀌면 CI 안 돌림" 결정과 충돌.
- 반대 조건(`paths-ignore`)의 같은 이름 더미 워크플로 → 두 파일의 경로 목록을 정확히 반대로 유지해야 하고 결과가 섞일 수 있음.
- 백엔드·프론트 CI를 한 워크플로로 통합 → BL-001의 분리 결정과 충돌, 변경 범위 큼.
