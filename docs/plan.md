# 계획서: 카나리(v2) latency 기반 분석 추가

- 작성일: 2026-06-14
- 관련 이슈/티켓: 없음 (직전 작업 "카나리 에러 격리 고도화"의 후속 — latency 게이트 추가)

## 목표
카나리(v2) 트래픽의 응답 지연(p99 latency)을 에러율과 동일한 수준으로 알람·AI 분석·점진 승급 판단에 반영하여, 에러는 없지만 응답이 느려지는 회귀(latency regression)를 탐지한다.

## 성공 기준
- [ ] `helm template helm/promotion-monitoring` 렌더링 성공, `CanaryV2LatencyHigh` 알람 1건 포함
- [ ] `.\gradlew.bat :aiops:compileJava` 통과 (AiOpsAgentService, CanaryRolloutScheduler, application.yaml)
- [ ] `.\gradlew.bat :aiops:test` 전체 통과 (CanaryRolloutSchedulerTest 신규/수정 케이스 포함)
- [ ] v2 p99 latency가 임계값(1000ms)을 초과하면 `CanaryRolloutScheduler`가 연속 정상 카운터를 리셋하는 동작이 단위 테스트로 검증됨

## 비범위 (Out of Scope)
- 3-way 카나리, v1/v2 외 추가 메트릭(처리량, 커스텀 비즈니스 메트릭)
- latency 기반 스케줄러 자체 롤백 — 기존 역할 분리 설계(알람 경로=격리, 스케줄러=점진 승급) 유지
- v1 대비 상대 비교(v2 p99 / v1 p99 비율) — 이번엔 절대 임계값(p99 > 1000ms)만 적용, 추후 운영 데이터 기반 재검토
- 시나리오10 분석 로직 전체 재작성 — latency 비교 PromQL 가이드 추가에 한정

## 단계별 작업 계획

### 단계 1: Prometheus 알람 CanaryV2LatencyHigh 추가 (높은 리스크 — 단계별 승인)
- 변경 파일: `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용 요약: Tier1-Business-Impact-SLO 그룹, `CanaryV2ErrorRateHigh` 옆에 추가.
  ```yaml
  - alert: CanaryV2LatencyHigh
    expr: |
      histogram_quantile(0.99,
        sum(rate(istio_request_duration_milliseconds_bucket{destination_version="v2"}[5m])) by (le)
      ) > 1000
      and
      sum(rate(istio_requests_total{destination_version="v2"}[5m])) > 0.05
    for: 1m
    labels:
      severity: warning
      tier: P2
    annotations:
      summary: "..."
      description: "..."
  ```
  severity:warning → tier:P2는 기존 Tier1 그룹의 warning 알람 6건과 동일한 컨벤션.
- 검증 방법: `helm template helm/promotion-monitoring` 실행 후 `CanaryV2LatencyHigh` 카운트 확인
- 롤백 방법: `git checkout -- helm/promotion-monitoring/files/alert-rules.yml`
- 예상 소요: 짧음

### 단계 2: AiOpsAgentService 시나리오10에 latency 비교 가이드 추가 (낮은 리스크 — 묶음 가능)
- 변경 파일: `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java` (76-87행 시나리오10)
- 변경 내용 요약: v1/v2 p99 latency 비교 PromQL 가이드 추가.
  ```
  histogram_quantile(0.99, sum(rate(istio_request_duration_milliseconds_bucket{destination_version="v2"}[5m])) by (le))
  (v1은 destination_version="v1"로 동일하게 조회)
  ```
  `CanaryV2LatencyHigh`(severity:warning) 발생 시 대응 가이드 추가: latency 저하는 에러보다 덜 치명적이므로 즉시 격리(proposeTrafficShift)보다 원인 분석을 우선하고, v1도 함께 느려진 경우(인프라 공통 이슈)와 v2만 느려진 경우(v2 자체 회귀)를 구분해서 보고하라는 문구.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: `git checkout -- aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java`
- 예상 소요: 짧음

### 단계 3: CanaryRolloutScheduler에 latency 게이트 추가 (높은 리스크 — 단계별 승인)
- 변경 파일: `aiops/src/main/java/aiops/aiops/scheduler/CanaryRolloutScheduler.java`, `aiops/src/main/resources/application.yaml`
- 변경 내용 요약: `checkService()`에서 에러율 체크 통과 후 v2 p99 latency를 `queryScalar()`로 조회(3번째 호출). `maxP99LatencyMs`(신규 `@Value("${canary.rollout.max-p99-latency-ms:1000}")`, 기본 1000.0) 초과 시 에러율 초과와 동일하게 `new CanaryState(weight, 0, state.lastProposedWeight())`로 연속 정상 카운터만 리셋(자체 롤백 없음). 조회 실패(`< 0`)면 기존 패턴과 동일하게 상태 변경 없이 보류.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: `git checkout -- aiops/src/main/java/aiops/aiops/scheduler/CanaryRolloutScheduler.java aiops/src/main/resources/application.yaml`
- 예상 소요: 보통

### 단계 4: 단위 테스트 작성/보강 (단계3과 묶음 실행 가능 — 테스트 코드, 낮은 리스크)
- 변경 파일: `aiops/src/test/java/aiops/aiops/scheduler/CanaryRolloutSchedulerTest.java`
- 변경 내용 요약:
  - `queryScalar` 호출이 2회(요청률, 에러율) → 3회(요청률, 에러율, latency)로 늘어나므로, 기존 5개 테스트의 `MockRestServiceServer.expect()` 개수를 모두 3의 배수 단위로 재조정.
  - 신규 테스트 2개: (a) v2 p99 latency > 1000ms → 연속 정상 카운터 리셋, `proposeTrafficShift` 미호출. (b) latency·에러율 모두 정상 → 카운터 정상 증가(기존 정상 흐름 동작 확인).
- 검증 방법: `.\gradlew.bat :aiops:test`
- 롤백 방법: `git checkout -- aiops/src/test/java/aiops/aiops/scheduler/CanaryRolloutSchedulerTest.java`
- 예상 소요: 보통

### 단계 5: 최종 검증
- 변경 파일: 없음 (검증 + 문서 갱신만)
- 변경 내용 요약: `.\gradlew.bat :aiops:build`, `helm template helm/promotion-monitoring`, `git diff --stat` 확인. `docs/checklist.md` 전체 갱신. `docs/design-notes.md`는 "코드만 봐서는 알 수 없는 결정"(절대 임계값 채택 이유, latency 메트릭 가용성 검증 항목)이 있을 때만 추가.
- 검증 방법: `.\gradlew.bat :aiops:build`, `helm template helm/promotion-monitoring`
- 롤백 방법: N/A
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: `istio_request_duration_milliseconds_bucket` 메트릭이 실제 EKS waypoint에서 노출되지 않을 수 있음 → 대응: `docs/checklist.md` "발견 사항"에 EKS 배포 후 확인 항목으로 기록 (직전 작업의 `destination_service_name` 검증 항목과 동일 패턴).
- 리스크 2: `CanaryRolloutSchedulerTest`의 `queryScalar` 호출 순서(요청률→에러율→latency) 변경으로 기존 5개 테스트의 `MockRestServiceServer` expectation 개수가 깨짐 → 대응: 단계3+4를 함께 진행하고, 전체 테스트를 3회 호출 기준으로 재검증.

## 의존성
- 단계1(알람)은 단계3(스케줄러)과 독립적 — 순서 무관하게 적용 가능하나 plan 순서대로 진행.
- 단계3,4는 직전 작업에서 작성한 `CanaryRolloutScheduler`/`CanaryRolloutSchedulerTest`를 수정하는 작업.
