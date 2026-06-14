# 계획서: 카나리 에러 격리 고도화 — v2 전용 알람 + 통계적 유의성 체크 + 점진적 자동 승급

- 작성일: 2026-06-14
- 관련 이슈/티켓: 없음 (직전 작업 "카나리 v1/v2 격리 인프라"의 현업 갭 보완 3건)

## 목표
카나리(v1/v2) 에러율 격리 인프라(직전 커밋 aaa8efa~306b9ac)의 운영 갭 3가지를 보완한다: (1) v2 트래픽 비중이 작아 전체 집계 알람이 안 뜨는 사각지대를 메우는 v2 전용 에러율 알람, (2) 통계적으로 무의미한 샘플로 잘못된 격리 판단을 막는 최소 요청 수 가드, (3) v2가 정상이면 트래픽 비중을 단계적으로 자동 승급(Slack 승인 기반)하는 스케줄러.

## 성공 기준
- [ ] `helm template helm/promotion-monitoring`이 신규 `CanaryV2ErrorRateHigh` 알람을 포함해 정상 렌더링됨
- [ ] AiOpsAgentService 시스템 프롬프트 시나리오 10에 최소 요청 수 가드(유의성 체크) 문구 추가됨
- [ ] `CanaryRolloutScheduler` 단위 테스트: v2 weight 0/100 스킵, 정상 N회 후 다음 단계 `proposeTrafficShift` 1회 호출, 비정상/샘플부족 시 카운터 미증가, 동일 weight 재제안 안 함(쿨다운)을 모두 검증
- [ ] `.\gradlew.bat :aiops:test` 통과
- [ ] `git diff --stat`로 비범위 침범 없음 확인

## 비범위 (Out of Scope)
- 카나리 최초 시작(v2 weight 0% → 10%) — 기존 `proposeTrafficShift` 수동 트리거로 충분
- 스케줄러 자체의 롤백 로직 — v2 에러율 급증은 신규 `CanaryV2ErrorRateHigh` 알람 → `analyze()` → 기존 시나리오10의 `proposeTrafficShift(v1=100,v2=0)` 경로로 이미 커버, 중복 구현 안 함
- v1/v2 외 3-way 카나리, p99/latency 기반 분석, Alertmanager resolved 파이프라인 재설계, HPA 상호작용 — 직전 작업과 동일하게 비범위 유지
- server-a/b/c 외 다른 VirtualService(gateway-service, aiops 등)로의 확장

## 단계별 작업 계획

### 단계 1: alert-rules.yml — CanaryV2ErrorRateHigh 알람 추가
- 변경 파일: `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용 요약: `Tier1-Business-Impact-SLO` 그룹에 `SystemErrorRateCritical`(132행)과 동일한 절대 임계값(5xx 비율 > 5%, `[5m]` 윈도우)을 `destination_version="v2"` 기준으로 적용하는 신규 알람 추가. min request rate guard로 `sum(rate(istio_requests_total{destination_version="v2"}[5m])) > 0.05`를 추가(2번 요건 — v2는 트래픽이 적으므로 기존 가드(>10)보다 낮은 값 사용).
- 검증 방법: `helm template helm/promotion-monitoring` 렌더링 성공 + 출력에 `CanaryV2ErrorRateHigh` 포함 확인
- 롤백 방법: 신규 alert 블록만 git revert
- 예상 소요: 짧음

### 단계 2: AiOpsAgentService — 시나리오 10에 유의성 체크 가이드 추가
- 변경 파일: `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java`
- 변경 내용 요약: 76-85행 시나리오 10에 "v2 요청률(`sum(rate(istio_requests_total{destination_version="v2"}[5m]))`)이 0.05 req/s 미만이면 에러율 수치가 통계적으로 무의미하므로 판단을 보류하고 추가 관찰" 문구 추가. 1번 알람과 동일한 가드 수치(0.05)로 일관성 유지.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: 추가한 문구만 git revert
- 예상 소요: 짧음

### 단계 3: KubernetesTools — getCanaryWeight(serviceName) 헬퍼 추가
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
- 변경 내용 요약: `kubectl get virtualservice <name> -n <ns> -o jsonpath={.spec.http[0].route[?(@.destination.subset=='v2')].weight}`로 현재 v2 가중치(0~100)를 조회하는 내부 헬퍼 메서드 추가. AI용 `@Tool`이 아닌 `CanaryRolloutScheduler` 전용 내부 메서드. 결과 없음/파싱 실패 시 -1 반환.
- 검증 방법: `KubernetesToolsTest`에 파싱/예외 처리 단위 테스트 추가
- 롤백 방법: 메서드만 git revert
- 예상 소요: 짧음

### 단계 4: CanaryRolloutScheduler 신규 추가 (점진적 자동 승급)
- 변경 파일(신규): `aiops/src/main/java/aiops/aiops/scheduler/CanaryRolloutScheduler.java`
- 변경 파일(수정): `aiops/src/main/java/aiops/aiops/AiopsApplication.java` (`@EnableScheduling` 추가), `aiops/src/main/resources/application.yaml` (`canary.rollout.*` 설정 추가)
- 변경 내용 요약:
  - `@Scheduled(fixedRateString)`로 주기 실행 (기본 5분, `canary.rollout.interval-ms`).
  - 대상 서비스(기본 server-a/b/c, `canary.rollout.services`) 각각에 대해 `getCanaryWeight` 조회 → 0 또는 100이면 스킵 + 상태 초기화.
  - `prometheusClient`로 v2 요청률과 v2 5xx 비율(`destination_version="v2"`, `destination_service_name="<svc>"`)을 조회.
  - 요청률 < 0.05(가드) → 판단 보류(카운터 유지). 5xx 비율 > 5% → 비정상(카운터 리셋). 정상이면 연속 정상 카운터 증가.
  - 연속 정상 카운터 >= `healthy-checks-required`(기본 2, 즉 10분) → steps(10→25→50→100) 중 다음 단계로 `kubernetesTools.proposeTrafficShift(service, 100-next, next, reason)` 호출(기존 Slack 승인 경로 재사용) 후 카운터 리셋 + 동일 weight 재제안 방지(쿨다운).
  - 관찰된 v2 weight가 이전 관찰값과 다르면(외부에서 변경됨) 상태 전체 리셋.
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: 신규 파일 삭제 + AiopsApplication/application.yaml 변경 revert
- 예상 소요: 보통

### 단계 5: 단위 테스트 작성
- 변경 파일: `aiops/src/test/java/aiops/aiops/scheduler/CanaryRolloutSchedulerTest.java`(신규), `aiops/src/test/java/aiops/aiops/tools/KubernetesToolsTest.java`(getCanaryWeight 케이스 추가)
- 변경 내용 요약:
  - v2Weight=0/100 → 스킵, 상태 미생성/초기화
  - 요청률 부족 → `proposeTrafficShift` 미호출, 카운터 불변
  - 5xx 비율 초과 → `proposeTrafficShift` 미호출, 카운터 리셋
  - 정상 N회 반복 → `proposeTrafficShift(v1=75,v2=25)` 1회 호출, 같은 weight로 추가 호출 시 재호출 안 됨(쿨다운)
  - weight 외부 변경 감지 → 상태 리셋
- 검증 방법: `.\gradlew.bat :aiops:test --tests "*CanaryRolloutScheduler*" --tests "*KubernetesTools*"`
- 롤백 방법: 테스트 파일만 git revert
- 예상 소요: 보통

### 단계 6: 최종 검증
- 변경 파일: 없음 (검증만)
- 변경 내용 요약: `.\gradlew.bat :aiops:build`, `helm template helm/promotion-monitoring`, `git diff --stat`로 비범위 침범 여부 확인. design-notes.md에 "점진 승급 트리거를 알람이 아닌 자체 스케줄러로 둔 이유", "연속 정상 카운터(시간 기반 대신) 채택 이유", "`destination_service_name` 라벨은 EKS 실제 메트릭과 다를 수 있어 배포 후 확인 필요"를 기록.
- 검증 방법: `.\gradlew.bat :aiops:build`, `helm template helm/promotion-monitoring`
- 롤백 방법: 해당 없음
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: `istio_requests_total`의 실제 라벨명(`destination_service_name` 등)이 추정과 다를 수 있음 → 로컬에서는 PromQL 문법/로직만 검증, 실제 라벨은 EKS 배포 후 Prometheus에서 확인(design-notes.md에 기록).
- 리스크 2: 스케줄러가 주기적으로 Slack에 중복 제안을 보낼 위험 → "제안 쿨다운"(동일 weight에서 재제안 안 함) 로직으로 방지.
- 리스크 3: `getCanaryWeight`의 jsonpath가 v2 subset route를 못 찾을 경우(-1) → 스케줄러는 "조회 불가, 스킵"으로 처리.

## 의존성
- 직전 작업(커밋 aaa8efa~306b9ac)의 istio-waypoint Prometheus 스크랩 잡, v1/v2 selector/VirtualService 구조에 의존.
- 신규 Gradle 의존성 없음 (Jackson ObjectMapper, `prometheusClient` RestClient, `@Scheduled` 모두 기존 의존성으로 충족).
