# 계획서: 결제 흐름 종단 간 p95 지연 알림 규칙 구현 (MIC-8)

- 작성일: 2026-06-08
- 관련 이슈/티켓: MIC-8 / https://linear.app/michi2012/issue/MIC-8

## 목표
결제 사가(서버 A 진입 → B/C 비동기 처리 → 서버 A의 PAID 최종 확정 + DB 업데이트)의 종단 간 소요 시간을 Micrometer 타이머로 계측하고, p95가 2초를 초과하면 발화하는 Prometheus 알림 규칙(`PaymentE2ELatencyHigh`)을 추가한다.

## 성공 기준
- [ ] `business_payment_e2e_duration_seconds` 타이머가 `tryCompleteSaga()`의 PAID 확정 성공 경로에서 기록된다 — `./gradlew :serverA:test --tests SagaOrchestratorServiceTest`로 검증
- [ ] `helm/promotion-monitoring/files/alert-rules.yml`과 `monitoring/prometheus/alert-rules.yml`에 `PaymentE2ELatencyHigh` 규칙이 추가된다
- [ ] `helm template` 렌더링이 오류 없이 통과한다 (컴파일 검증)
- [ ] `./gradlew :serverA:test` 전체 통과
- [ ] 변경 사항이 아래 "비범위"를 침범하지 않는다 (`git diff --stat`로 최종 확인)

## 비범위 (Out of Scope)
- 실패·타임아웃된 사가의 지연 측정 — 성공(PAID 확정) 경로에서만 계측한다. 실패율은 기존 `SagaExpired`/`PaymentBusinessErrorRate*` 알림이 이미 담당
- 임계치(2초)의 동적/자동 조정
- 실 클러스터 배포 후 알림 실제 발화 여부 검증 — `helm template` 렌더링 통과까지만 진행 (EKS 배포 후 별도 검증 권장)
- Grafana/SRE 대시보드에 새 패널 추가
- 구간별(A→B, B→C 등) 개별 알림 규칙 신설

## 단계별 작업 계획

### 단계 1: 종단 간 지연 타이머 계측 추가
- 변경 파일: `serverA/src/main/java/promotion/serverA/service/SagaOrchestratorService.java`, `serverA/src/test/java/promotion/serverA/service/SagaOrchestratorServiceTest.java`
- 변경 내용 요약: `MeterRegistry`를 주입받아 `@PostConstruct`에서 `Timer.builder("business_payment_e2e_duration_seconds").description(...).register(meterRegistry)`로 초기화 (기존 `serverC/PaymentService`의 `Counter` 초기화 패턴과 동일하게). `tryCompleteSaga()`에서 `orderRepository.updateStatusIfPending(...)`이 성공(`updated > 0`)한 직후, `sagaStateService.getCreatedAt(orderId)`로 시작 시각(epoch millis)을 가져와 `System.currentTimeMillis() - createdAt`을 타이머에 기록한다 (`createdAt > 0`일 때만). 기존 테스트에 타이머 등록·기록 호출을 검증하는 케이스를 추가한다.
- 검증 방법: `./gradlew :serverA:test --tests SagaOrchestratorServiceTest`
- 롤백 방법: 추가한 필드 선언·`@PostConstruct` 초기화·`record()` 호출부를 git revert로 제거
- 예상 소요: 보통

### 단계 2: PaymentE2ELatencyHigh 알림 규칙 추가
- 변경 파일: `helm/promotion-monitoring/files/alert-rules.yml`, `monitoring/prometheus/alert-rules.yml`
- 변경 내용 요약: 기존 `PaymentHighLatency`(Tier2-Application-Performance, `helm/.../alert-rules.yml:86`)와 동일한 패턴으로 `PaymentE2ELatencyHigh` 규칙을 추가한다. `expr`은 `histogram_quantile(0.95, sum(rate(business_payment_e2e_duration_seconds_bucket[5m])) by (le)) > 2`와 최소 요청 수 조건(`sum(rate(business_payment_e2e_duration_seconds_count[5m])) > 0`)의 `and` 결합으로 구성하고, `severity: warning`, `tier: P2`로 분류한다. 두 알림 규칙 파일(helm 배포용/docker-compose 로컬용)이 서로 동기화된 상태로 유지되고 있어 동일하게 반영한다.
- 검증 방법: `helm template helm/promotion-monitoring | Select-String "PaymentE2ELatencyHigh"` (렌더링 확인) — `monitoring/prometheus/alert-rules.yml`은 YAML 문법 육안 확인
- 롤백 방법: 추가한 alert 블록 제거 (git revert)
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: `getCreatedAt`이 `0L`을 반환하는 비정상 케이스(Redis 키 만료 등)에서 잘못된(매우 큰) duration이 기록될 수 있음 → 대응: `createdAt > 0`일 때만 기록하도록 가드 조건 추가
- 리스크 2: 새 메트릭이 실제로 트래픽을 받기 전까지는 알림이 발화하지 않아 "죽은 규칙"처럼 보일 수 있음 → 대응: 코멘트로 "신규 메트릭 — 배포 후 데이터 누적 필요"를 명시. 실측 데이터 기반 임계치 재검토는 MIC-9(이미 생성된 후속 티켓)에서 다룸

## 의존성
- `SagaStateService.getCreatedAt(orderId)` — Redis에 저장된 사가 시작 시각(이미 구현되어 있음, `SagaStateService.java:30,80`)
- `MeterRegistry` — Spring Boot Actuator/Micrometer 자동 구성 (기존 `serverC/PaymentService`에서 동일하게 사용 중)
