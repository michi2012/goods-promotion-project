# 포트폴리오 & 이력서

---

## 🧑‍💻 굿즈 구매 선착순 프로모션 시스템

**기간**: 26.05 ~ 진행 중  
**유형**: 개인 프로젝트  
**관련 링크**: https://github.com/[본인 저장소]

---

### 📝 프로젝트 개요

1vCPU 제약 환경에서 1만 명 동시 선착순 구매 요청을 처리하기 위해, Kafka Saga Orchestration · Outbox + Debezium CDC · Redis 3단계 동시성 제어 · CQRS를 결합한 분산 이벤트 시스템을 구축했다. 동기식 기준선 60 TPS에서 시작해 단계별 아키텍처 개선으로 최종 10,300 TPS를 달성했으며, AIOps 장애 자동 분석과 전 계층 옵저버빌리티 파이프라인까지 직접 설계·구현했다.

---

### 🛠️ 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 / 프레임워크 | Java 21, Spring Boot 3 |
| 메시지 브로커 | Apache Kafka, Debezium CDC |
| 캐시 | Redis, Caffeine |
| 장애 내성 | Resilience4j (Circuit Breaker) |
| AI / AIOps | Spring AI (ChatClient, Tool Calling) |
| DB | MySQL (DB-per-service: promotion DB / payment DB) |
| 모니터링 | Prometheus, Grafana, Tempo, Loki, Alertmanager, OpenTelemetry Collector, Vector, cAdvisor |
| 부하 테스트 | k6 |
| 빌드 / 인프라 | Gradle 멀티 모듈, Docker Compose |

---

### 🚀 문제 해결 경험

- **[[성능 최적화] 비동기 큐 · Bulk Insert · UUID v7(B+Tree 순차 삽입) · Java 21 가상 스레드]** Tomcat 스레드의 DB I/O 점유를 해소하고, 시간 기반 순차 UUID로 Bulk Insert 순서를 정렬해 B+Tree 페이지 분할 최소화 및 Gap Lock 데드락 예방 (기준선 60 TPS → 630 TPS, 10.5배 향상 / p95 18s → 4.3s)

- **[[Kafka] HTTP 직통 → 이벤트 드리븐 전환 + SKIP LOCKED Outbox 폴링]** 서버 간 DB 집중 부하를 분산 소비 구조로 전환해 처리량과 안정성 동시 확보 (630 TPS → 3,300 TPS, 5.2배 / DB 처리 12분 → 15초, 48배 단축)

- **[[Redis] Caffeine · SETNX · Lua DECRBY로 Kafka 발행 전 재고 선점]** 재고 부족 요청을 Kafka 발행 이전에 차단해 하위 서비스(serverB·C) 불필요 처리 부하 원천 제거 (Kafka 기준선 3,300 TPS → 7,900 TPS, 2.4배 / p95 1.6s → 1.0s)

- **[[CQRS] DB 없는 읽기 전용 서버 분리 (Redis 뷰)]** 선착순 이벤트 폭증 시 구매 쓰기와 주문 상태 조회가 동일 서버 자원을 경합하는 병목을 구조적으로 제거 (리소스 2배 증설 대비 serverB 분리 시 TPS 1.7배 효과, 4,175 TPS → 7,200 TPS)

- **[[Debezium CDC] Transactional Outbox + binlog 즉시 발행]** Outbox 폴링의 주기 지연과 DB 폴링 부하를 제거하고 traceparent 자동 주입으로 분산 트레이스 연결 (9,300 TPS → 10,300 TPS, +10.7% / p95 0.4s 단축)

- **[[Kafka Saga] Orchestration + DLT → DB 저장으로 분산 트랜잭션 일원화]** serverA 단독 Saga 흐름 제어, 10분 타임아웃 EXPIRED 처리, 재시도 소진 메시지 dead_letter 영속화로 PG 미정산 차단

- **[[AIOps] Spring AI ChatClient + Tool Calling 장애 자동 분석]** Alertmanager 웹훅 수신 시 Prometheus·Loki·Tempo·Git 자동 조회 → 연쇄 장애 인과관계 분석 → Slack 보고서 자동 발송 구현

- **[[모니터링] OTel · Vector · cAdvisor 전 계층 수집 + 6-Tier SRE 대시보드]** 메트릭·로그·트레이스 단일 Grafana 통합, P0/P1/P2 알람 → AIOps 파이프라인 연결

- **[[AI 개발환경] CLAUDE.md + 도메인별 Skills + docs/ 선행 설계로 Claude Code 환각·토큰 낭비 억제]** 코드 착수 전 설계 문서(plan·context·checklist)를 Claude Code가 직접 작성·저장해 컨텍스트를 외부화하고, 자동 생성된 아키텍처 스냅샷·인프라 다이어그램을 코드 작업 시 참조해 코드베이스 오인지 차단

---
---

## 포트폴리오 상세

---

### 아키텍처 다이어그램 (Phase 3 최종)

```
[Client]
   │
   ▼
[serverA: Saga Orchestrator]
 Caffeine(품절캐시) → Redis SETNX(중복방어) → Redis Lua(재고선점)
   │ purchase_events (직접 발행, 동기 3s)
   ▼
[Kafka] ←── Debezium CDC ──── [Outbox Table in promotionDB]
   │                                    ▲
   ├── payment-request ──▶ [serverC: 결제] ──▶ PG사
   │                           │ payment-result (Outbox+CDC)
   ├── order-status-update ──▶ [serverB: CQRS 읽기] ──▶ Redis 뷰
   │                           │ status-update-result (직접 발행)
   └── SagaResultConsumer ─────┘
         성공: Order→PAID / 실패: 보상 트랜잭션 + 망취소
```

```
[옵저버빌리티]
 서버 OTLP traces ──▶ OTel Collector ──▶ Tempo
 서버 로그 (공유 볼륨) ──▶ Vector ──▶ Loki
 Redis·Kafka·MySQL exporter + cAdvisor ──▶ Prometheus
 Prometheus ──▶ Alertmanager ──▶ MCP(AIOps) ──▶ Slack
 Tempo·Loki·Prometheus ──▶ Grafana SRE 대시보드
```

---

### [성능 개선] 60 TPS → 10,300 TPS: 4단계 아키텍처 진화

**테스트 환경**: 단일 호스트 Docker Compose (전체 스택: serverA·B·C + Kafka + MySQL + Redis + Debezium + 모니터링), 각 컨테이너 1vCPU · 2GB 자원 제한  
**부하 시나리오**: k6 / 1,000 VU / 20초간 100,000건 스파이크

---

🚨 **문제**: 1vCPU 제약 환경에서 동기식 처리 구조는 Tomcat 스레드가 DB I/O 내내 점유되어 커넥션 풀이 고갈됐다. 기준선 측정 결과 60 TPS / p95 18s로 1만 명 동시 처리 목표에 한참 미치지 못했다. 이후 단계별로 병목 원인을 실측 비교하며 아키텍처를 점진적으로 전환했다.

---

#### Phase 1 — Server A 단독 최적화 (60 → 630 TPS)

⚖️ **대안 비교 및 기술 선정**:

| 시도 | 핵심 변경 | 결과 | 판단 |
|------|----------|------|------|
| V1 (기준선) | 동기식 처리 | 60 TPS / p95 18s | — |
| V2 | 비동기 큐 + Bulk Insert | 370 TPS / p95 3.9s | 채택 |
| V3 | UUID v7 오름차순 정렬 → B+Tree 페이지 분할 최소화 | 460 TPS / p95 3.9s | 채택 |
| V4~V7 | 격리수준 하향, 스레드 풀 축소 실험 | 340~400 TPS | **탈락** |
| V8 | Java 21 가상 스레드 | **630 TPS / p95 4.3s** | 채택 |

- **격리 수준 하향 (V4~V7 탈락)**: Tomcat max-threads를 200 → 20·50으로 축소하면 CPU-bound 환경에서 유리할 것으로 가설 세웠으나, I/O 대기가 지배적인 환경에서는 오히려 340~400 TPS로 하락해 탈락.
- **가상 스레드 (V8 채택)**: I/O 블로킹 구간에서 OS 스레드를 반환하므로 동일 자원에서 동시 처리 가능한 I/O 요청 수가 증가.

🛠️ **조치**:
- `LinkedBlockingQueue` 적재 후 즉시 202 반환 → Tomcat 스레드 즉시 해제
- `rewriteBatchedStatements=true` + JdbcTemplate batchUpdate → 단건 INSERT 수백 회를 단일 I/O로 통합
- Bulk Insert 직전 traceId(UUID v7) 오름차순 정렬 → B+Tree 우측 순차 삽입으로 페이지 분할 최소화. 멀티 인스턴스 확장 시 Gap Lock 데드락도 예방
- Spring Boot `spring.threads.virtual.enabled=true` 활성화

📈 **성과**:
- 동기 기준선(60 TPS) 대비 **630 TPS, 10.5배 향상** / p95 18s → 4.3s, **76% 단축**

---

#### Phase 2 — Kafka 아키텍처 전환 (630 → 3,300 TPS / DB 처리 12분 → 15초)

⚖️ **대안 비교 및 기술 선정**:

| 단계 | 핵심 변경 | TPS | DB 처리 | 판단 |
|------|----------|:---:|:-------:|------|
| 큐 한도 유지 (기준선) | 기존 구조 | 630 | 50초 | — |
| 큐 한도 제거 | 모든 요청 202 수용 | 5,700 | **12분** | **탈락** |
| Kafka 도입 | 서버 간 이벤트화 | 2,700 | **15초** | 채택 |
| Kafka + 가상 스레드 | I/O 블로킹 해소 | **3,300** | 15초 | 채택 |
| 대조: Kafka 리소스 → serverA 증설 | 단순 자원 증설 | 4,300 | **1분 30초** | 탈락 |

- **큐 한도 제거 (탈락)**: 5,700 TPS로 응답률은 급등했으나 DB로 한꺼번에 몰리며 실제 처리에 12분 소요. 단순 큐 조정으로는 근본 해결 불가.
- **단순 리소스 증설 (탈락)**: TPS는 4,300으로 높으나 DB 처리에 1분 30초 — Kafka 대비 6배 불안정. Kafka가 단순 자원 증설보다 처리 안정성 면에서 우세.
- **Kafka 채택**: 서버 간 이벤트화로 소비 속도를 제어할 수 있어 DB 집중 부하를 분산.

🛠️ **조치**:
- 서버 간 HTTP 직통을 Kafka 토픽 구독으로 전환 (7개 토픽 설계)
- Outbox 폴링 스케줄러에 `SKIP LOCKED` 적용 → 다중 인스턴스 중복 발행 방지
- Kafka 전체 스택에서도 가상 스레드 적용 유지

📈 **성과**:
- Phase 1 기준선(630 TPS) 대비 **3,300 TPS, 5.2배 향상** / p95 4.3s → 1.6s  
- DB 처리 시간: 큐 한도 제거 상태 12분 → **15초, 48배 단축**

---

#### Phase 3 — 아키텍처 고도화 (3,300 → 10,300 TPS)

⚖️ **대안 비교 및 기술 선정**:

**쓰기 100% 단일 파이프라인**

| 단계 | 핵심 변경 | TPS | p95 | 판단 |
|------|----------|:---:|:---:|------|
| Phase 2 최종 (기준선) | Kafka + 가상 스레드 | 3,300 | 1.6s | — |
| Redis Lua + Orchestration Saga | 재고선점 + Saga 일원화 | **7,900** | **1.0s** | 채택 |
| 품절 로컬 캐시 | Caffeine 즉시 차단 | 9,400 | 1.9s | 채택 |
| Outbox 폴링 (대조 기준) | 폴링 방식 | 9,300 | 1.9s | — |
| CDC 전환 (최종) | Debezium binlog 즉시 발행 | **10,300** | **1.5s** | 채택 |

**쓰기 20% / 읽기 80% 혼합**

| 단계 | 핵심 변경 | TPS | 판단 |
|------|----------|:---:|------|
| serverA 읽기 포함 (리소스 2배, 대조) | 읽기·쓰기 경합 | 4,175 | 탈락 |
| CQRS — serverB 읽기 전담 | 경합 제거 | **7,200** | 채택 |
| CQRS + 품절 캐시 | Caffeine 1차 필터링 | **11,600** | 최대 |

---

### [Redis] DB 락 없는 3단계 동시성 제어

🚨 **문제**: 선착순 구매 요청이 동시에 수천 건 몰릴 때 DB `SELECT FOR UPDATE`로 재고를 검증하면 락을 보유한 채로 DB 커넥션을 점유해 커넥션 풀이 고갈됐다. 낙관적 락(`@Version`) 방식은 충돌 시 재시도가 폭발적으로 증가해 오히려 부하를 가중시켰다. 재고가 있는 요청뿐 아니라 이미 품절된 상품에 대한 반복 요청도 Redis·Kafka·DB까지 전달되어 불필요한 리소스를 낭비했다.

⚖️ **대안 비교 및 기술 선정**:

- **DB 비관적 락 (기각)**: `SELECT FOR UPDATE`가 재고 차감 트랜잭션 전 구간 동안 커넥션을 점유 → 동시 요청 수만큼 커넥션이 블로킹되어 고트래픽에서 커넥션 풀 고갈 필연적.
- **DB 낙관적 락 (기각)**: 충돌 발생 시 버전 불일치로 롤백 후 재시도를 반복 → 경합이 심할수록 재시도 폭탄으로 DB 부하가 오히려 가중되는 구조. 1,000 VU 스파이크 환경에서 재시도 증폭은 허용 불가.
- **Lettuce 단순 원자적 연산 DECR (기각)**: `DECR` 자체는 원자적이나 "재고 0 미만 방지" 조건 분기(`GET → 비교 → DECR`)를 단일 명령으로 묶는 방법이 없어 레이스 컨디션 발생. 재고가 1일 때 여러 요청이 동시에 조건 통과 후 `DECR`하면 음수가 되어 재고 과다 차감이 일어난다.
- **Redisson 분산 락 (기각)**: 락 획득(`tryLock`) → 비즈니스 로직 → 락 해제(`unlock`) 구조로 Redis 왕복이 최소 2회 발생. 단순 재고 차감 연산에 락 점유·해제 오버헤드가 더해지고, 락 만료(watchdog) 갱신 로직까지 추가해야 하는 운영 복잡도가 증가.
- **Redis Lua 스크립트 + 단계별 사전 차단 — 최종 채택**: `GET → 비교 → DECRBY`를 단일 Lua 스크립트로 원자적 실행해 레이스 컨디션을 원천 차단. 그 앞단에 Caffeine(품절 캐시)·SETNX(중복 구매) 레이어를 배치해 Lua 스크립트까지 도달하는 요청 자체를 줄인다.

🛠️ **조치**:

| 단계 | 구현 | 역할 |
|------|------|------|
| 1단계 | Caffeine 로컬 캐시 (TTL 60s) | Redis 왕복 없이 품절 즉시 차단 |
| 2단계 | Redis SETNX (`user:purchase:{userId}:{goodsId}`, TTL 1h) | 동일 유저 중복 구매 원천 차단 |
| 3단계 | Redis Lua 스크립트 (`GET → 비교 → DECRBY` 원자적 실행) | 재고 선점. 부족 시 차감 발생 안 함 |

- Lua 스크립트는 재고 충분 시 DECRBY 후 남은 재고 반환, 부족 시 `-1` 반환으로 DB 접근 없이 요청 즉시 차단.
- Kafka 발행 실패 시 2·3단계를 즉시 롤백(SETNX 삭제 + INCRBY)해 Redis 선점 상태와 DB 상태의 정합성을 유지.

📈 **성과**:
- Kafka 기준선(3,300 TPS) 대비 **7,900 TPS, 2.4배 향상** / p95 1.6s → **1.0s**
- DB 레이어까지 도달하는 요청을 Redis 3단계에서 사전 차단 → 불필요한 DB 커넥션 점유 제거

---

### [Outbox + CDC] Transactional Outbox + Debezium 기반 신뢰성 있는 메시지 발행

🚨 **문제**: `kafkaTemplate.send()` 직접 호출 방식은 DB 트랜잭션 커밋 성공 후 Kafka 발행이 실패하는 순간 메시지가 유실된다. 특히 Saga 내부에서 Order 저장·재고 차감과 payment-request 발행이 분리되면 DB는 PENDING 상태인데 결제 서버로 이벤트가 전달되지 않아 Saga가 영구 미완료 상태로 방치된다. 또한 Outbox 폴링 스케줄러 방식은 스케줄러 주기만큼 발행이 지연되고, 폴링 쿼리 자체가 DB에 지속적인 부하를 발생시킨다.

⚖️ **대안 비교 및 기술 선정**:

- **kafkaTemplate 직접 발행 (기각)**: DB 커밋 후 Kafka 발행 실패 시 메시지 유실. DB 트랜잭션과 원자적으로 묶을 방법이 없음.
- **Outbox 폴링 스케줄러 (기각)**: 스케줄러 주기(100ms~1s) 동안 발행 지연이 발생하며, `SELECT ... WHERE status='PENDING'` 폴링 쿼리가 DB에 지속적 부하를 유발.
- **Debezium CDC — 최종 채택**: MySQL binlog를 구독해 Outbox 테이블의 INSERT 이벤트를 감지하면 즉시 Kafka에 발행. DB 폴링 부하가 없고, DB 트랜잭션과 메시지 발행이 원자적으로 묶임. `traceparent` 컬럼을 Kafka 헤더로 자동 주입해 분산 트레이스도 연결.

🛠️ **조치**:

- `OrderCommandService`, `SagaOrchestratorService`의 Kafka 발행 대상 메시지를 `@Transactional` 범위 내 Outbox 테이블 INSERT로 전환.
- `OutboxEventService.save()`가 MDC에서 현재 traceId/spanId를 읽어 W3C traceparent 형식으로 조립해 Outbox 레코드에 저장.
- Debezium Connector가 binlog를 구독해 Outbox INSERT 감지 → Kafka 메시지 헤더에 traceparent 자동 주입.
- serverC Consumer들이 헤더에서 traceparent를 추출해 Child Span 생성 → 구매 요청부터 결제 완료까지 단일 분산 트레이스 연결.
- `purchase_events`(구매 접수) 토픽만 직접 발행 유지: 이 시점은 아직 DB Order가 없어 Outbox 트랜잭션을 묶을 대상이 없으며, Kafka 발행 실패 시 Redis 선점 상태를 즉시 롤백하는 보상으로 정합성을 유지.

📈 **성과**:
- Outbox 폴링 기준선(9,300 TPS) 대비 **10,300 TPS, +10.7%** / p95 1.9s → **1.5s, 0.4s 단축**
- DB 폴링 부하 제거 및 DB 트랜잭션 커밋과 메시지 발행의 원자성 보장

---

### [Kafka Saga] Orchestration Saga + DLT 전략

🚨 **문제**: serverA(주문)·serverB(상태 갱신)·serverC(결제)가 참여하는 분산 트랜잭션에서 결제 성공 후 주문 상태 갱신이 실패하거나, 망취소 메시지가 유실되면 PG사에 승인된 금액이 환불되지 않는 미정산 상태가 발생한다. Choreography Saga는 각 서비스가 직접 보상 이벤트를 발행하는 구조라 전체 흐름을 추적하기 어렵고, 보상 누락이 발견되지 않을 위험이 있다.

⚖️ **대안 비교 및 기술 선정**:

- **Choreography Saga (기각)**: 보상 로직이 각 서비스에 분산되어 장애 시 보상 흐름을 추적하기 어렵다. 특정 서비스가 다운되면 보상 이벤트 자체가 발행되지 않아 누락 탐지가 불가능하다.
- **2PC (기각)**: 모든 참여자가 커밋 전 Prepare 상태를 유지해야 하므로 DB 커넥션 점유 시간이 길고, 코디네이터 장애 시 전체 트랜잭션이 블로킹된다.
- **Orchestration Saga — 최종 채택**: serverA가 단독으로 Saga 흐름을 제어하므로 보상 트랜잭션 누락 위험이 없고, 장애 원인을 한 곳에서 추적 가능하다.

🛠️ **조치**:

- serverA가 Redis Hash에 `SagaState`를 보관하며 payment-result와 status-update-result 두 결과를 수집 후 판정.
- 성공 시: Order → PAID, order-completed 발행 (Outbox+CDC).
- 실패 시: Order → FAILED, Redis 재고·유저마킹 복구, DB 재고 복구(`increaseStockAtomically`), payment-cancel 발행 (결제 성공 건에 한해 PG 망취소).
- 멱등성: `updateStatusIfPending()` — PENDING 상태일 때만 변경, 중복 실행 시 0 반환으로 이중 처리 차단.
- 타임아웃: `SagaTimeoutScheduler`가 60초마다 SCAN → 생성 후 10분 초과 미완료 Saga → EXPIRED 처리.
- DLT 전략: 재시도 소진 메시지를 DB `dead_letter` 테이블에 저장, `POST /api/v1/admin/dlt/{dltId}/retry` API로 수동 재처리. `payment-cancel.DLT`는 자동 재처리 없이 알람 → 수동 정산.

📈 **성과**:
- 분산 보상 흐름 일원화로 보상 누락 위험 제거
- 10분 Saga 타임아웃으로 Redis 상태 영구 잠김 방지
- DLT 메시지 DB 영속화로 PG 승인 후 환불 실패 건 관리자 추적 가능

---

### [CQRS] serverB 읽기 전용 분리

🚨 **문제**: 플래시세일 중 구매 쓰기(serverA)와 주문 상태·재고 조회가 동일 서버에서 경합하면 쓰기 처리량이 저하된다. 조회 요청 자체도 serverA의 DB 커넥션을 소비하기 때문에, 트래픽이 혼합될수록 선착순 처리에 필요한 자원이 감소한다.

⚖️ **대안 비교 및 기술 선정**:

- **serverA에 읽기·쓰기 통합 + 리소스 2배 증설 (기각)**: 리소스를 2배로 늘려도 읽기·쓰기 경합 자체는 해소되지 않는다. 실측 결과 4,175 TPS에 그쳐 CQRS 분리보다 열위.
- **serverB 읽기 전용 분리 — 최종 채택**: DB 없이 Redis 뷰만 조회하므로 serverA의 DB 커넥션·CPU를 전혀 소비하지 않는다.

🛠️ **조치**:

- serverB는 DB 없음. Kafka를 통해 전달된 주문 상태·재고 이벤트를 Redis Hash에 갱신.
- `OrderStatusKafkaConsumer` → Redis Hash 상태 갱신 → `status-update-result` 직접 발행 → serverA SagaResultConsumer 수집.
- 트레이드오프: 주문 직후 상태 조회 시 수십 ms 지연 가능한 최종 일관성 모델. 의도된 설계.

📈 **성과**:
- 동일 워크로드에서 serverA 포함(리소스 2배) 4,175 TPS 대비 **7,200 TPS, 1.7배 TPS 효과**
- 읽기·쓰기 경합이 핵심 병목임을 실증 — 리소스 증설보다 구조적 분리가 효과적

---

### [AIOps] Spring AI 기반 장애 자동 분석

🚨 **문제**: 분산 시스템에서 P1 이상 장애 발생 시 메트릭·로그·트레이스가 각 저장소에 분산되어 있어 원인 파악과 보고서 작성에 시간이 소요된다. 특히 DB 부하 → CDC 지연 → Kafka 블로킹 → HTTP 5xx 같은 연쇄 장애는 단일 지표만으로 원인을 특정하기 어렵다.

⚖️ **대안 비교 및 기술 선정**:

- **단순 알람 전달 (기각)**: Alertmanager에서 Slack으로 알람을 그대로 전달하면 원인 없이 숫자·이름만 포함된 알람이 수신되어 담당자가 직접 다시 조회해야 한다.
- **고정 스크립트 기반 분석 (기각)**: 장애 유형마다 별도 스크립트가 필요하고, 연쇄 장애의 계층별 인과관계를 텍스트로 서술하기 어렵다.
- **Spring AI ChatClient + Tool Calling — 최종 채택**: 도구 호출 순서를 LLM이 동적으로 판단하므로 장애 유형과 무관하게 관련 지표를 선택적으로 조회하고 인과관계를 서술할 수 있다.

🛠️ **조치**:

```
Alertmanager webhook → MCP 서버(8085)
  ① 중복 억제 (동일 groupLabels 지문, 30분 TTL)
  ② Spring AI ChatClient + ObservabilityTools Tool Calling
     - queryPrometheusMetrics  : 에러율·가용성 현황
     - queryDatabaseHealth     : HikariCP 대기·슬로우 쿼리·Redis 메모리
     - queryLokiLogs           : 대상 서비스 최근 5분 ERROR 로그
     - queryTempoTrace         : traceId 추출 시 분산 트레이스 조회
     - queryRecentCommits(60)  : 최근 1시간 배포 이력 (장애 10분 이내 커밋 → 롤백 권장)
  ③ 연쇄 장애 인과관계 서술
  ④ Slack 보고서 발송 (요약·원인·영향 범위·권장 조치·핵심 지표)
```

- `resolved` 알람은 AI 분석 없이 "정상 회복" 메시지만 발송해 불필요한 LLM 호출 차단.

📈 **성과**:
- P1 이상 알람 발생 시 Prometheus·Loki·Tempo·Git 자동 조회 후 Slack 보고서 자동 발송
- 중복 억제로 동일 장애에 대한 30분 내 중복 분석 차단

---

### [모니터링] 전 계층 옵저버빌리티 파이프라인

🚨 **문제**: 분산 환경에서 서버 애플리케이션·Kafka·Redis·MySQL·컨테이너 각각의 메트릭과 로그가 분산되어 있어 장애 발생 시 어떤 계층에서 문제가 시작됐는지 파악하기 어렵다. 트레이스 연결 없이는 서비스 간 지연 전파 경로도 추적 불가능하다.

🛠️ **조치**:

**수집 파이프라인**

| 신호 유형 | 수집 방식 | 저장소 |
|----------|----------|--------|
| 분산 트레이스 | OTel Collector (OTLP gRPC) | Tempo |
| 애플리케이션 로그 | Vector (공유 볼륨 tail) | Loki |
| 인프라 메트릭 | redis-exporter·redis-b-exporter·kafka-exporter·mysql-a-exporter·mysql-c-exporter | Prometheus |
| 컨테이너 자원 | cAdvisor (host cgroups) | Prometheus |
| 애플리케이션 메트릭 | Prometheus scrape (Micrometer) | Prometheus |

**Grafana SRE 대시보드 — 6-Tier 계층 구조**

| Tier | 범주 | 주요 지표 |
|------|------|----------|
| Tier 1 | Business Impact & SLO | 결제 API 가용성(SLI), 에러 예산 소진 속도(Burn Rate) |
| Tier 2 | Application RED | p95 응답 지연, 5xx 에러율, 결제 처리량(RPS) |
| Tier 3 | System Saturation | JVM Heap, 가상 스레드 수, CPU 사용률 |
| Tier 4 | Infrastructure | Redis 메모리·연결 수, Kafka 컨슈머 렉, MySQL 커넥션·슬로우 쿼리·버퍼풀 히트율 |
| Tier 5 | Business Metrics | 결제 시도/성공/실패 RPS, 환불 요청 건수·처리 지연 |
| Tier 6 | Resilience | 서킷 브레이커 상태(OPEN/HALF_OPEN), 외부 API 호출 실패율 |

**Prometheus 알람 규칙 (P0·P1·P2)**

| 심각도 | 기준 | 대표 알람 |
|--------|------|----------|
| P0 | 즉각 수기 대응 필요 | PG 결제 승인 후 망취소까지 연달아 실패 → 미정산 발생 |
| P1 | 서비스 중단·SLO 위반 임박 | 인스턴스 다운, 가용성 < 99.9%, 서킷브레이커 OPEN, DB 커넥션 풀 포화 |
| P2 | 전조 증상·성능 저하 | p95 > 500ms, 5xx 에러율 > 1% 3분 지속, Kafka 컨슈머 렉 > 500건, MySQL 슬로우 쿼리 > 1/s |

Alertmanager → MCP 웹훅 → AIOps 자동 분석 → Slack 보고서 발송 파이프라인으로 연결.

📈 **성과**:
- 메트릭·로그·트레이스 전 계층 단일 Grafana 대시보드에서 조회 가능
- 비즈니스 임팩트 → 인프라 상태 순으로 장애 영향 범위를 빠르게 좁힐 수 있는 계층형 구조 확보

---

### [AI 개발환경] CLAUDE.md + 도메인별 Skills 기반 LLM 개발 워크플로우

🚨 **문제**: LLM 어시스턴트를 코드 작성에 활용할 때 네 가지 반복적인 문제가 발생했다.

1. **환각(Hallucination)으로 인한 코드베이스 오인지**: 어시스턴트가 실제 파일 구조·메서드 시그니처·테이블 스키마를 잘못 추정해 존재하지 않는 메서드를 호출하거나, 실제 컬럼명과 다른 쿼리를 생성하는 사례가 반복됐다. 특히 MySQL 실행 계획을 물어보면 스키마를 직접 조회하지 않고 추정해 잘못된 인덱스 힌트를 제안했다.
2. **도메인 컨벤션 위반**: Kafka DLT를 자동 재시도(`@RetryableTopic`)로 구현하거나, JPA 트랜잭션을 Controller에 붙이거나, Entity를 직접 반환하는 등 이 프로젝트의 설계 결정과 충돌하는 범용 패턴이 반복 생성됐다.
3. **과잉 설계**: 요청하지 않은 추상화 레이어·유연성 추가·인접 코드 리팩터링이 자동으로 따라붙어 diff가 의도보다 2~3배 커지고, 리뷰 부담이 증가했다.
4. **단일 리뷰어 한계**: codex-review가 놓친 문제를 독립 시각으로 교차 검증하는 수단이 없어 동일 패턴의 오류가 반복 통과됐다.

⚖️ **대안 비교 및 기술 선정**:

- **단순 시스템 프롬프트 (기각)**: 매 대화마다 컨텍스트를 반복 입력해야 하고, 프로젝트별 설계 결정이 누락되면 범용 패턴으로 되돌아간다.
- **CLAUDE.md 행동 원칙만 사용 (부족)**: 전반적인 행동 원칙(과잉 설계 금지, 수술적 변경 등)은 지킬 수 있으나, Kafka DLT 전략·JPA 연관관계 규칙·API 응답 형식 같은 도메인 특화 컨벤션은 담을 수 없어 레이어별 정합성이 여전히 어시스턴트에 달려 있다.
- **CLAUDE.md + 도메인별 Skills 분리 — 최종 채택**: 행동 원칙(CLAUDE.md)과 도메인 지식(Skills)을 역할별로 분리해 관리. Skills는 작업 유형에 맞춰 선택적으로 로드되므로 컨텍스트 낭비 없이 정밀하게 코드 품질을 제어할 수 있다.

🛠️ **조치**:

**CLAUDE.md — 4원칙 행동 지침**

| 원칙 | 핵심 규칙 |
|------|----------|
| `Think Before Coding` | 불확실한 부분은 가정하지 않고 먼저 질문. 여러 해석이 존재하면 옵션 제시 후 선택 요청 |
| `Simplicity First` | 요청 범위를 초과하는 추상화·유연성·에러 핸들링 추가 금지. 200줄이 50줄로 쓸 수 있으면 재작성 |
| `Surgical Changes` | 변경한 모든 라인이 요청과 직접 연결되어야 함. 인접 코드 개선 금지. 발견한 데드코드는 보고만 |
| `Goal-Driven Execution` | 작업을 검증 가능한 목표로 변환 후 착수. 다파일 변경은 /plan 선행 필수 |

추가로 Spring Boot 레이어 규칙을 명시: Controller에 @Transactional 금지, Entity 직접 반환 금지, open-in-view false 필수, 민감 정보 로그 노출 금지.

**도메인별 Skills** (`.claude/skills/`)

각 Skills 파일은 트리거 조건, 이 프로젝트의 금지 패턴, 올바른 구현 예시를 포함한다.

| 스킬 | 트리거 조건                         | 핵심 컨벤션 예시 |
|------|--------------------------------|----------------|
| `kafka` | Kafka·DLT·Debezium·아웃박스 등 언급 시 | DLT는 DB dead_letter 저장 + 수동 재처리 API. `@RetryableTopic` 사용 금지. Outbox는 호출자 `@Transactional` 내부에서만 호출 |
| `jpa` | 엔티티·리포지토리·N+1·트랜잭션 등 언급 시      | N+1은 fetch join 또는 @EntityGraph로 해결. @Transactional은 Service 레이어만. `@Data/@Setter` 금지 |
| `api` | 컨트롤러·DTO·엔드포인트 등 작업 시          | 응답은 ApiResponse 래퍼 사용. DTO는 레이어별 분리. Bean Validation은 @Validated + @Valid 구분 |
| `exception` | 예외·에러 응답·상태 코드 등 언급 시          | GlobalExceptionHandler 집중 관리. 에러 응답 형식 통일 |
| `testing` | 테스트 작성 시                       | @WebMvcTest는 컨트롤러 단위, @DataJpaTest는 리포지토리, Testcontainers는 통합 테스트 |

**docs/ 선행 설계 — 환각 억제 + 토큰 낭비 감소의 핵심**

다파일 변경 작업 시 `plan` 스킬이 코드 작성 전 아래 세 파일을 먼저 생성한다.

| 파일 | 역할 |
|------|------|
| `docs/plan.md` | 구현 전략·변경 대상 파일·제약 조건 |
| `docs/context.md` | 설계 배경·도메인 결정 이유 |
| `docs/checklist.md` | 단계별 완료 기준 |

Claude Code가 구현을 시작하기 전 설계 의도를 직접 문서로 작성하고 확인을 받는다. 이후 긴 작업 도중 컨텍스트가 압축되더라도 Claude Code가 세 파일을 재로드해 이전 결정을 복원할 수 있어 **롱컨텍스트 환각과 토큰 낭비를 동시에 억제**한다.

**자동화 워크플로우 스킬**

| 스킬 | 역할                                                                         |
|------|----------------------------------------------------------------------------|
| `arch-snapshot` | 전체 코드베이스 스캔 → 아키텍처 스냅샷 자동 생성. 코드 작업 시 참조해 파일 구조·메서드 위치 오인지 차단              |
| `infra-diagram` | docker-compose.yml + monitoring/ 스캔 → Mermaid 인프라 토폴로지 자동 생성. 포트·의존성 추정 방지 |
| `codex-review` | codex 멀티 에이전트 리뷰로 클로드 코드가 놓친 문제 교차 검증                                      |

MySQL MCP를 Claude Code에 연결해 `EXPLAIN`·`SHOW CREATE TABLE`로 실제 실행 계획과 스키마를 직접 조회하도록 구성했다. 추정 기반의 잘못된 인덱스 힌트·존재하지 않는 컬럼 참조를 차단하는 보조 수단으로 활용한다.

**운영 규칙**

- 변경 파일 2개 이상인 작업은 `/plan` 명령 선행 필수 — 코드 작성 전 plan/context/checklist 3파일을 먼저 생성해 제약 조건을 확인
- 작업 완료 시 자가 점검 보고: ✅완료한 것 / 🔍검증 결과 / ⚠️발견했지만 손대지 않은 것 / ❓확인 필요 사항
- 테스트 코드 작성은 Claude, `gradlew test` 실행은 사용자 직접 — 실행 로그가 필요한 경우 붙여넣기 후 수정

📈 **성과**:
- **환각 억제**: MySQL MCP로 실제 스키마·실행 계획 직접 조회 → 존재하지 않는 컬럼 참조, 잘못된 인덱스 힌트 제안 차단. `arch-snapshot`·`infra-diagram` 선행 생성으로 파일 구조·서비스 연결 관계 오인지 최소화
- **컨벤션 일관성**: Skills 도입 전 Kafka DLT를 `@RetryableTopic` 자동 재시도 방식으로 생성하던 것이, kafka/SKILL.md 트리거 후 dead_letter DB 저장 + 수동 재처리 API 패턴으로 일관 생성
- **교차 검증**: `/codex-review`이중 검증으로 단일 리뷰어가 놓친 문제를 보완
- **실행 통제**: `plan` 스킬로 다파일 변경 착수 전 제약 조건·단계를 문서화해 중간 방향 전환 최소화. CLAUDE.md 4원칙으로 변경 diff를 요청 범위 내로 통제
