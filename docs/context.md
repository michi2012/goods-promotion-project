# 맥락 노트: 카나리(v2) latency 기반 분석 추가

## 왜 이 방식을 선택했는가

직전 작업(카나리 에러 격리 고도화, 커밋 895e609/9b3c3b0/6c90b42)은 5xx 에러율만으로 카나리(v2) 정상 여부를 판단한다. v2가 에러 없이 정상이어도 응답속도가 느려지는 회귀(N+1 쿼리, 캐시 미적용 등)는 현재 구조로 탐지할 수 없다.

업계 표준 카나리 분석 도구(Flagger, Netflix Kayenta 등)는 에러율과 latency(p99)를 함께 게이트로 사용한다. 이번 작업은 이 패턴을 따라, 사용자와 합의된 다음 설계로 진행한다:
- 적용 범위: 알람(`CanaryV2LatencyHigh`) + AI 분석 가이드(시나리오10) + 점진 승급 스케줄러 게이트 — 에러율과 동일한 커버리지.
- 비교 기준: 절대 임계값(p99 > 1000ms).
- 알람 severity: warning (5xx 에러보다 덜 치명적인 신호로 취급).

## 검토했으나 채택하지 않은 대안

### 대안 A: v1 대비 상대 비교 (v2 p99 > v1 p99 × 1.5)
- 무엇: v1/v2 p99를 모두 쿼리해서 비율로 회귀를 직접 판단.
- 왜 안 썼나: 점진 승급이 진행될수록 v1 트래픽 비중이 줄어들어 v1 p99 표본이 부족해지고 노이즈가 커진다. 양쪽 쿼리가 필요해 알람 expr와 스케줄러 로직 모두 복잡도가 증가한다. 절대 임계값으로 먼저 도입하고, 운영 데이터가 쌓인 뒤 상대 비교 추가를 검토하기로 함.

### 대안 B: severity=critical
- 무엇: `CanaryV2ErrorRateHigh`와 동일하게 critical로 설정.
- 왜 안 썼나: latency 저하는 5xx 에러보다 덜 치명적인 신호이며, 즉시 트래픽 격리보다 AI의 원인 분석(v1도 같이 느려졌는지, v2만의 문제인지)이 먼저 필요하다는 판단. `severity: warning` → `tier: P2`는 alert-rules.yml의 기존 warning 알람 6건과 동일한 컨벤션.

### 대안 C: 스케줄러 자체 롤백
- 무엇: latency 초과 시 `CanaryRolloutScheduler`가 직접 `proposeTrafficShift`로 v2 격리를 제안.
- 왜 안 썼나: 직전 작업의 역할 분리 설계(`docs/design-notes.md` "카나리(v2) 점진적 자동 승급: 스케줄러와 알람의 역할 분리")를 그대로 따른다. latency 초과 시에도 에러율 초과와 동일하게 "연속 정상 카운터만 리셋"하고, 격리 판단은 알람 경로(`analyze()`)에 위임한다.

## 기존 코드베이스 컨벤션
- `alert-rules.yml`: `severity: warning` → `tier: P2` (Tier1 그룹 내 6개 알람에서 확인된 일관된 매핑).
- `CanaryRolloutScheduler.checkService()`: `queryScalar()`로 Prometheus 스칼라 조회 — 결과 없음=0.0(트래픽 없음), 조회/파싱 실패=-1.0(보류).
- 단위 테스트: `aiops/src/test/java/aiops/aiops/scheduler/CanaryRolloutSchedulerTest.java` — `MockRestServiceServer` 사용 시 모든 `expect()`는 첫 요청 전에 선언해야 함 (`IllegalStateException` 방지).

## 관련 파일/위치
- `helm/promotion-monitoring/files/alert-rules.yml:170-186` — `CanaryV2ErrorRateHigh` (포맷 참조)
- `helm/promotion-monitoring/files/prometheus.yml:75-95` — `istio-waypoint` 스크랩 잡 (latency 히스토그램도 동일 경로로 노출된다고 가정, EKS 확인 필요)
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java:76-87` — 시나리오10
- `aiops/src/main/java/aiops/aiops/scheduler/CanaryRolloutScheduler.java` — `checkService()`, `queryScalar()`
- `aiops/src/main/resources/application.yaml:54-61` — `canary.rollout.*` 설정
- `docs/design-notes.md` — "카나리(v2) 점진적 자동 승급: 스케줄러와 알람의 역할 분리" 섹션, latency 게이트도 이 원칙을 따름

## 외부 참조
없음
