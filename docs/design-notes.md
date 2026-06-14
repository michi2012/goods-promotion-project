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

**결정:** `SagaTimeoutScheduler`가 60초마다 Redis SCAN으로 미완료 Saga 탐지

- 키 패턴: `saga:state:*`
- 타임아웃 기준: 생성 후 **10분** 초과
- 결과: `handleSagaFailure(orderId, "TIMEOUT")` → Order 상태 **EXPIRED** (FAILED와 구분)
- 이유: 결제 응답이 없는 Saga를 무한 대기하면 Redis 상태 영구 잠김

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
