# CHANGELOG

모든 릴리즈 이력을 기록합니다. [Conventional Commits](https://www.conventionalcommits.org/) 기반.

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
