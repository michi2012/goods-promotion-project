# CHANGELOG

모든 릴리즈 이력을 기록합니다. [Conventional Commits](https://www.conventionalcommits.org/) 기반.

---

## v1.11.1 — 2026-06-16

> GitHub Actions 기반 CI/CD 파이프라인 구축 및 문서(README/아키텍처) 정비

### 🔧 Chore / Docs
- GitHub Actions CI/CD 파이프라인 추가 (96b2900) — JDK 21 기준 9개 서비스 matrix 병렬 빌드, helm upgrade promotion-app values.yaml 키 구조 기반 --set 파라미터 구성, prod 카나리 시작 step 주석 포함(CanaryRolloutScheduler 연동용)
- README.md 및 아키텍처 md 파일 수정 (68bf9c4)

---

## v1.11.0 — 2026-06-16

> CS 자동 응대 챗봇(cs-bot) Phase1 출시 및 aiops DLT 자동 재처리·인프라 챗봇 고도화

### ✨ New Features
- CS 자동 응대 챗봇(cs-bot) Phase1 백엔드 구현 및 Helm 배포 설정 (a6edf86) — Spring AI 기반 CS 챗봇 모듈 신규 출시. 주문·결제·환불 조회, 환불 요청, 에스컬레이션 도구 포함. Istio VirtualService/DestinationRule 포함 Helm 차트 추가.
- aiops DLT 자동 재처리 도구 추가 (7341e9e) — Prometheus `PurchaseDltAccumulated` 알람 수신 시 UNRESOLVED DLT를 자동 분류 후 복구 시도 (retryable → 자동 재처리, non-retryable → Slack 수동 안내). serverA Gauge 메트릭 및 목록 조회 API 포함.
- [aiops] 인프라 챗봇 라벨 컨벤션 반영 + 인프라 도구 화이트리스트 추가 (9a57401)

---

## v1.10.0 — 2026-06-15

> codebot에 주문/결제/사용자 데이터 조회 도구를 추가하고, 코드 검색·PR 생성 흐름을 git-sync 기반 로컬 조회와 diff 미리보기로 신뢰성 있게 개선했습니다.

### ✨ New Features
- codebot 데이터 조회/코드 검색/PR 흐름 신뢰성 개선 — order/payment/user DB 화이트리스트 조회 도구(executeQuery) 추가, CodeSearchTools/PullRequestTools를 git-sync 사이드카 기반 로컬 git 조회로 재작성(diff 미리보기·filePath 자동보정), createIssue/createFixPullRequest 템플릿 개선, Slack mrkdwn 변환, RouterService 스레드별 sticky 캐시 추가 (82e9141)

### 🔧 Chore / Docs
- pre-tool-check 훅 차단 조건 좁히기 (b4dadf0)

---

## v1.9.0 — 2026-06-14

> Router-Worker 챗봇 아키텍처 도입과 Istio 카나리(v1/v2) 트래픽 격리·자동 점진 승급(에러율+latency) 인프라를 추가했습니다.

### ✨ New Features
- [router] aiops(Router)/codebot(Worker) 분리 — Slack 이벤트 의도 분류 후 라우팅, codebot은 Pyroscope 핫스팟 분석·단일파일 PR 자동 생성 담당 (#5) (cb269ab)
- [istio] server-a/b/c v2(canary) Deployment 추가 및 DestinationRule/VirtualService 가중치 라우팅 인프라 구성 (#5) (cb269ab)
- [aiops] `CanaryV2ErrorRateHigh`/`CanaryV2LatencyHigh` 알람 및 `CanaryRolloutScheduler` 추가 — v2 정상 상태 연속 확인 시 점진 승급 제안, 비정상 시 카운터 리셋(격리는 알람 경로 전담) (#5) (cb269ab)
- [aiops] 데드락 의심 시 스레드 덤프 확보 후 롤링 재시작, 자동조치 완료 후 Linear 감사 티켓 자동 생성 (#5) (cb269ab)

---

## v1.8.0 — 2026-06-13

> 프론트엔드(React+TS+Vite) 기반 구축 및 AI 워크플로우 PM 커맨드 파이프라인 정비

### ✨ New Features
- 프론트엔드 기반 구축: React+TS+Vite 주문 상태 조회 화면, orval(OpenAPI codegen), shadcn/ui, Vitest+RTL+Playwright 테스트 환경 및 PM 커맨드 파이프라인(`/system-design`, `/project-plan`, `/spec-design`, `/project-status`, `/k6-test`) 신설, `serverB` springdoc-openapi 연동 (#4) (6673709)
- [aiops] 알람 진단 보고서에 구조적 개선 제안 섹션 추가 (462bd48)

### 🔧 Chore / Docs
- AI 워크플로우 운영 규칙 보완 (문서 비대화, 디버깅 루프, DB 마이그레이션 등) (d0195aa)
- v1.7.0 변경 내용을 README에 반영 및 AI 워크플로우 섹션 추가 (c06946e)

---

## v1.7.0 — 2026-06-08

> 결제 종단 간 p95 지연 모니터링·알림 체계 구축 및 Linear MCP 워크플로우 연동 정비

### ✨ New Features
- 결제 종단 간 p95 지연 알림 추가 및 Linear 워크플로우 정비 (MIC-8) (818d997)
- plan/incident/release-notes에 Linear MCP 연동 추가 (29386d0)
- 요구사항 명세 작성 및 티켓 생성 명령어 추가 (spec-draft, spec-to-tickets) (9ad6207)

### 🔧 Chore / Docs
- 결제 종단 간 p95 지연 패널을 SRE 대시보드에 추가 (#3) (edb97ba)
- Linear MCP 워크플로우 통합 작업 계획/맥락/체크리스트 (f98694b)
- 사용하지 않는 스케줄러 lock 파일 삭제 (9362aad)
- test: 테스트 실행을 위한 필수 환경변수 추가 (fdbc866)

### 🔗 포함된 이슈
- [MIC-8](https://linear.app/michi2012/issue/MIC-8/결제-흐름-종단-간-p95-지연-알림-규칙-정의-임계치-2초) — 결제 흐름 종단 간 p95 지연 알림 규칙 정의 (임계치 2초)

---

## v1.6.0 — 2026-06-07

> Pyroscope 연속 프로파일러 도입으로 핫스팟 분석 및 성능 진단 기능 추가

### ✨ New Features
- aiops에 Pyroscope 핫스팟 조회 도구 추가 및 로컬 검증 환경 구성 (e72b8e7)
- Pyroscope continuous profiler Helm 차트 추가 및 서비스 연동 (eed1e9e)

### 🔧 Chore / Docs
- Pyroscope profiler 도입 및 E2E 검증 결과 문서 반영 (ea04141)

---

## v1.5.0 — 2026-06-07

> Karpenter Helm 차트 통합 및 AIOps 관측성 도구 확장

### ✨ New Features
- [aiops] getClusterStatus에 노드 상태 조회 추가 (d59c44c)
- [karpenter] promotion-karpenter Helm 차트 추가 (9941f45)
- [aiops] queryKafkaLag 추가 및 시스템 프롬프트 업데이트 (372c3d8)
- [aiops] getIstioMeshStatus 추가 및 proposeTrafficShift 가중치 검증 (3c7223b)

### 🐛 Bug Fixes
- [karpenter] Karpenter v1 disruption 버그 수정 및 limits 상향 (8522a92)

### 🔧 Chore / Docs
- README 및 port.md에 Karpenter 추가 내용 반영 (a6d7f45)
- README aiops 도구 목록 업데이트 — queryKafkaLag, getIstioMeshStatus 추가 (6ac9e43)
- Karpenter 차트 추가 작업 계획 기록 (외 2건) (462fa90, db2168e, 2d80095)

---

## v1.4.0 — 2026-06-06

> user-service를 전체 스택(모듈·Docker Compose·Helm·Gateway)에 통합하고, 인프라 다이어그램을 Cloudflare WAF 기준으로 업데이트한 릴리즈.

### ✨ New Features
- helm user-service 차트 추가 (374caca)
- docker compose user-service 및 mysql-user 추가 (78a033a)
- gateway user-service 라우팅 및 JWT 인증 필터 추가 (fa9365e)
- user-service 모듈 통합 (35fc2aa)

### 🔧 Chore / Docs
- server-a DB 이름 `promotion` → `order` 변경 (8b4109a)
- CLAUDE.md docs 관리 원칙 추가 및 작업 기록 업데이트 (a433e6a)
- infra 다이어그램 K8s에서 AWS WAF 제거 (Cloudflare로 대체), 엣지 라벨 단순화 (45e2808, 5717252)
- user-service 통합 반영 (arch-snapshot, infra-diagram, README) (9680581)

---

## v1.3.0 — 2026-06-06

> Istio Ambient 기반 서비스 메시 인프라 전환과 AIOps 트래픽 제어 자동화 연동 완료

### ✨ New Features
- [AIOps] Istio 트래픽 제어 연동 — `proposeTrafficShift` / `executeTrafficShift` 구현 (d42f0e1)
- [Istio] Ambient Helm 차트 및 VirtualService/DestinationRule 추가 (a0ea029)
- [AIOps] K8s 자동화 및 K8s 인프라 전환 (bad93e6)

### 🐛 Bug Fixes
- [ObservabilityTools] `queryDatabaseHealth` 누락된 예외 처리 추가 (c7c02e7)

### 🔧 Chore / Docs
- CLAUDE.md E2E 테스트 원칙 및 K8s 스킬 보완 (6810eee)
- Istio Ambient 도입 및 AIOps 트래픽 제어 반영 — docs 업데이트 (8d86e56)

---

## v1.2.0 — 2026-06-06

> AIOps K8s 자동화(HPA 조정·Helm 롤백·롤링 재시작 Slack 승인 플로우) 및 K8s 인프라 전환 전면 도입

### ✨ New Features

**AIOps K8s 자동화**
- K8s 클러스터 조회·스케일·재시작 도구 추가 — `getClusterStatus`, `proposeHpaPatch`, `proposeRolloutRestart`, `proposeHelmRollback` (0ad0e5c)
- Slack Block Kit 승인/거절 버튼 플로우 구현 (35d3360)
- AIOps 시스템 프롬프트 개선 — HPA/Kafka/Rollback 시나리오 판단 로직 (e5898d5)
- Gateway HPA(min:1 max:3) 및 KubeHPAOverprovisioned P3 알람 추가 (bc6bc74)
- Prometheus K8s 알람 규칙 및 kube-state-metrics 추가 (83a2cb5)
- SRE 대시보드 K8s Tier5 패널 추가 (cc90262)

**K8s 인프라**
- Helm 차트 구성 — promotion-app / promotion-infra / promotion-monitoring (5c6584f)
- AIOps RBAC(ServiceAccount + ClusterRole) 및 deployment 설정 추가 (b7f458b)
- K8s/local 환경 분리 — `application-k8s.yml`, `SPRING_PROFILES_ACTIVE` (361c8b7)
- logback local/k8s 프로필 분기 — 파일 로그 vs stdout JSON (d3308d6)

**Gateway**
- Spring Cloud Gateway 토큰버킷 Rate Limiting + ALB Ingress 설정 (9c7482a)
- MSA 인프라 레이어 추가 — gateway-service, discovery-service (a096598)

### 🐛 Bug Fixes
- AIOps RBAC 누락 권한 추가 — autoscaling/HPA, deployments patch, secrets (e263b7a)

### ♻️ Refactor
- OTLP 엔드포인트 플레이스홀더+기본값 방식으로 통일 (fcf3b27)

### 🔧 Chore / Docs
- K8s/Helm 공통 스킬 추가 (410b4d6)
- K8s 자동화 도구 및 tier 라벨 반영 문서 업데이트 (외 5건)
- MSA 환경 구성을 위한 Gradle 독립 빌드 및 Docker Compose 분리 (ed3712b)

---

## v1.1.1 — 2026-06-03

> SRE 모니터링 정확도 개선 및 aiops 모듈 리네이밍

### 🐛 Bug Fixes
- SLI 결제 완료율 게이지 100% 초과 표시 방지 (clamp_max 적용) (`6e721d8`)
- SRE 모니터링 알람 정합성 및 SLI 지표 정확도 개선 (`a362e49`)

### ♻️ Refactor
- mcp 모듈을 aiops로 리네이밍 및 모니터링 연동 추가 (`11854fa`)

### 🔧 Chore / Docs
- 중간 단계 정보 로그 레벨 DEBUG 하향 조정 및 트레이싱 샘플링 1%로 하향 (`e5b2c63`)

---
Full diff: `git log v1.1.0..HEAD --oneline`

---

## v1.1.0 — 2026-06-02

> Saga EXPIRED 상태 모니터링 강화 및 구매 API 반환 타입 개선

### ✨ New Features
- Saga EXPIRED 상태 처리 카운터 메트릭 및 P1 알람 추가 (58d863d)

### ♻️ Refactor
- 구매 API 반환 타입을 orderId 포함 DTO로 변경 (4458e0d)
- 사용하지 않는 Redis hold TTL 키 제거 (a0635bd)

### 🔧 Chore / Docs
- 주문 상태 폴링 방식 변경에 따른 README.md 및 arch-snapshot.md 수정 (9892cae)
- 불필요한 port.md 제거 및 README.md 오류 수정 (378cafa)

---
Full diff: `git log v1.0.1..HEAD --oneline`

---

## v1.0.1 — 2026-05-31

> v1.0.0 이후 안정성 보강 및 인프라 개선 패치. Redis 롤백 처리 강화, Flyway 마이그레이션 도입, 테스트 환경 고도화.

### ♻️ Refactor

- Redis 롤백 예외 처리 강화 및 안정성 보강 (5017100)

### 🔧 Chore / Docs

- [serverA] Flyway 도입 및 JPA 스키마 자동 생성 속성 변경 (f5e403d)
- SagaState 임시 홀딩 TTL 30초로 조정 (1261531)
- PromotionService.acceptPurchase() 변경에 따른 README.md 수정 (4bc073d)
- 장애 원인 분석 및 DB 마이그레이션 위험도 Claude Code 자동화 스크립트 추가 (075ae8a, e2d641c 외 1건)

### 🧪 Test

- MySQL 테스트컨테이너 도입 및 Redis 롤백 예외 처리 강화 (1ddb0a8)

---

Full diff: `git log af315ca..HEAD --oneline`

---

## v1.0.0 — 2026-05-31

> Redis·Kafka·Saga 기반 고성능 선착순 프로모션 플랫폼 초기 릴리즈 — 분산 트랜잭션, 관측성, AIOps 자동화까지 포함한 프로덕션 레디 아키텍처 완성

### ✨ New Features

**분산 트랜잭션 / 데이터 정합성**
- Orchestration 기반 Saga 패턴 적용 및 타임아웃 복구 로직 구현 (74d0a0e)
- 트랜잭셔널 Outbox 패턴 도입 및 Kafka 비동기 발행 최적화 (875cb32)
- Outbox 발행 방식을 Polling → Debezium CDC 기반으로 전환 (0f9a54a)
- DB-per-service 적용 및 서버C 전용 MySQL/CDC 분리 (f3b59da)
- SAGA 보상 트랜잭션 구현 및 멱등성 방어 적용 (23e89d8)
- Redis Stream 기반 Reliable 워커 및 자동 장애 복구 시스템 구현 (a317693) (외 4건)

**Kafka 이벤트 아키텍처**
- Kafka 기반 이벤트 소비 및 DLT 처리 아키텍처 구현 (1c69525)
- 컨슈머 예외 처리 및 DLT 라우팅 로직 구현, DLT 수동 재처리 관리자 API (3349e43, c7da4a3) (외 2건)

**캐싱 / Redis**
- RedisStockService 재고 검증 Lua 스크립트 구현 (b0fa41f)
- Redis 및 Kafka 기반 대기열 아키텍처 전환 (3468de4)
- Goods 엔티티 생성 시 Redis 재고 적재 로직 추가 (9a4797b)

**관측성 / 모니터링**
- SRE 3대 관측성(Metrics·Logs·Traces) 통합 모니터링 아키텍처 구축 (29b8195)
- AIOps 기반 장애 자동 분석 및 Slack 알림 파이프라인 구축 (f089c91)
- AIOps 에이전트 분석 품질 및 운영 안전성 고도화 (ee90e4f)
- Resilience4j 서킷브레이커 지표 수집 및 Grafana 대시보드 추가 (182a48f)
- cAdvisor 도입 및 MySQL 자원 모니터링 구축 (f52522f)
- mysql-exporter 도입 및 Alertmanager 데이터소스 연결 (30c03c5)

**서버C (결제)**
- 결제 내역 조회 API 엔드포인트 및 서비스 로직 추가 (c57c183)
- 결제 승인 로직 및 Saga 트랜잭션 참여자 구현 (1feb3e6)
- 벌크 주문 처리 엔진 구현 및 결제-DB 정합성 보장 로직 추가 (3e3066d)
- PG 결제 연동 인터페이스 및 장애 시뮬레이션용 Mock 구현 (33ec4b6)

**Resilience / 장애 격리**
- Resilience4j 서킷브레이커 도입 및 외부 서버(A·B·C) 장애 격리 (cac1ed5, d6aa03b, e67a082)
- CQRS 패턴 읽기 전용 뷰 및 이벤트 컨슈머 구현 (93d70bf)
- Codex MCP 연동 및 교차 코드 리뷰어 에이전트 도입 (45b595c)

### 🐛 Bug Fixes

- [serverA] Kafka 발행 동기화 및 Saga 타임아웃 단축으로 결제 안정성 강화 (f2914b4)
- [kafka] 컨슈머 및 설정 규약 위반 사항 일괄 수정 (f2f3600)
- Redis 설정 버그 플레이스홀더로 수정 (69989e5)
- INSERT IGNORE → ON DUPLICATE KEY UPDATE 전환 및 배치 최적화 (87d31a5)
- 중복 Scheduling 빈 제거 (8d90d28)

### ⚡ Performance

- Caffeine 로컬 캐시 도입으로 Redis 부하 최적화 (5544c48)
- 재고 상태 기반 로컬 캐시 동적 만료 정책 적용 (016d6ee)
- Outbox SKIP LOCK 쿼리 전환으로 스케줄러 스레드 간 데드락 회피 (cb93c2b)
- UUID v7 도입 및 Outbox Flusher 불필요한 정렬 로직 제거 (822f80c)
- 품절 확정 PENDING 레코드 DB 조회 없이 bulk FAIL 처리 (b05e8b3)
- 품절된 대기열 요청의 DB Insert 조기 차단(Drop) 로직 구현 (5cd71d5) (외 3건)

### ♻️ Refactor

- 상위 패키지명 `promotion`으로 일괄 변경 (d722379)
- Outbox 발행 방식 Polling → Debezium CDC 전환 (0f9a54a)
- [serverC] 도메인 모델 명칭 변경 (`FinalOrder` → `Payment`) (f5c7d22)
- Kafka 비동기 발행 전환 및 전송 실패 시 Redis 롤백 적용 (80c0efe)
- Outbox 선점 로직을 네이티브 SKIP LOCKED 쿼리로 전환 (84ad7c3)
- 이벤트 기반 품절 캐시 동기화 로직 도입 (f042d6f) (외 16건)

### 🔧 Chore / Docs

- JaCoCo 커버리지 분석 스크립트 및 Claude Code 가이드 문서 추가 (bb0a1d6)
- README.md 전체 아키텍처 mermaid 및 성능 테스트 결과 보고서 업데이트 (d36aed0, a4e1e52)
- docker-compose 카프카 토픽 및 Redpanda Console UI 추가 (31a1d06)
- 가상 스레드 최적화 및 컨테이너 워커 JVM 옵션 적용 (6a19100, 0c50210) (외 다수)

---

Full diff: `git log HEAD --oneline`
