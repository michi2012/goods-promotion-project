# 맥락 노트: 카나리 에러 격리 고도화 — v2 전용 알람 + 통계적 유의성 체크 + 점진적 자동 승급

## 왜 이 방식을 선택했는가

직전 작업(카나리 v1/v2 격리 인프라, 커밋 aaa8efa~306b9ac) 완료 후 "현업수준" 관점에서 식별된 3가지 갭:
1. v2 비중이 작으면 전체 집계 알람(`SystemErrorRateCritical`)이 안 뜸 → v2 전용 알람 필요
2. 트래픽이 적을 때 에러율 수치의 통계적 유의성 부족 → 최소 요청 수 가드 필요
3. "정상 시 자동 승급" 로직 부재 — 현재는 "이상 시 격리"만 존재 → 주기적 스케줄러로 보완

3번의 트리거 메커니즘이 가장 큰 설계 결정이었다. 기존 `analyze()`는 Alertmanager 알람("문제 발생")으로만 트리거되는데, "v2가 정상이니 승급하라"는 알람은 존재하지 않는다("정상 지속"은 알람화하기 어려움). 따라서 aiops에 `@Scheduled` 컴포넌트를 신규 추가하기로 결정했다(사용자 선택).

자동 승급도 기존 Slack 승인 거버넌스(`proposeTrafficShift`)를 그대로 통과시키기로 했다 — "자동"의 의미는 "감지+제안의 자동화"로 한정하고, 실제 트래픽 변경 적용은 사람이 승인한다. 기존 6개 `proposeXxx` 도구와 동일한 패턴을 유지해 새로운 승인/거버넌스 경로를 만들지 않는다.

"정상 지속 시간" 판단은 `Instant`/`Duration` 기반 시간 추적 대신 "연속 정상 체크 횟수"로 구현한다 — 스케줄러 interval × 횟수가 사실상 동일한 의미를 가지면서, 단위 테스트에서 시간 모킹 없이 메서드를 N번 호출하는 것만으로 검증 가능하다(Simplicity First).

## 검토했으나 채택하지 않은 대안

### 대안 A: Alertmanager resolved 알림을 3번 트리거로 활용
- 무엇: 기존 `PrometheusWebhookController` 경로로 "알람 해제(resolved)"를 받아 "정상 회복" 신호로 사용
- 왜 안 썼나: 알람이 한 번도 발화하지 않으면 resolved 자체가 없어 "정상 지속"을 감지할 수 없다. 또한 resolved 처리 로직이 현재 aiops에 전혀 없어 신규 구현 범위가 더 커진다. 사용자도 주기적 스케줄러(옵션 A)를 선택했다.

### 대안 B: 스케줄러에 자체 롤백(에러율 급증 시 즉시 0%로) 로직 포함
- 무엇: `CanaryRolloutScheduler`가 에러율 급증을 감지하면 직접 `proposeTrafficShift(v1=100,v2=0)` 호출
- 왜 안 썼나: 1번에서 추가하는 `CanaryV2ErrorRateHigh` 알람 → `analyze()` → 기존 시나리오10 로직이 동일 상황을 이미 처리한다. 두 경로가 같은 조치를 중복 제안하면 Slack에 중복 알림이 발생한다. "비정상 시 격리"는 알람 경로, "정상 시 승급"은 스케줄러로 역할을 분리했다.

### 대안 C: 점진 증가 단계/가드값을 values.yaml(Helm)에 노출
- 무엇: steps/healthy-checks-required/threshold 등을 Helm values로 분리해 환경별 오버라이드 가능하게 함
- 왜 안 썼나: 이 값들은 aiops 애플리케이션 로직 파라미터이며 K8s 리소스 스펙이 아니다. `application.yaml`(+환경변수)로 충분하고, 기존 aiops 설정(`k8s.namespace` 등)도 동일 패턴을 따른다.

## 기존 코드베이스 컨벤션
- 도구 패턴: `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`의 `proposeXxx` 메서드 — `approvalService.propose(actionType, params, reason)` + `slackService.sendBlockKit(...)` + 승인ID 반환 문자열.
- RestClient 빈: `aiops/src/main/java/aiops/aiops/config/RestClientConfig.java` — `prometheusClient` 빈 재사용 (신규 빈 추가 안 함).
- 설정 패턴: `aiops/src/main/resources/application.yaml` — `@Value("${...:default}")` 생성자 주입, 별도 `@ConfigurationProperties` 클래스 없이 단순 값 주입 (`KubernetesTools` 생성자 참고).
- kubectl 실행: `KubernetesTools.runKubectl(String... args)` — `List<String>` 기반 `ProcessBuilder`, 커맨드 인젝션 방지 (CLAUDE.md 절대 금지 사항).
- 테스트 패턴: `aiops/src/test/java/aiops/aiops/tools/KubernetesToolsTest.java` — `MockitoExtension`, `@Mock` 의존성 주입, AssertJ `assertThat`.

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java:76-85` — 카나리 v1/v2 에러율 비교 시나리오10 (직전 작업에서 추가, 2번 가이드 추가 대상)
- `helm/promotion-monitoring/files/alert-rules.yml:131-148` — `SystemErrorRateCritical`/`Warning` (1번의 참고 패턴, min-rate guard 포함)
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java:134-175` — `getIstioMeshStatus`, `proposeTrafficShift` (3번에서 재사용/확장)
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java:444-463` — `runKubectl` (3번 `getCanaryWeight`에서 재사용)
- `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java:318-324` — `callPrometheus` (Prometheus 호출 패턴, 4번 스케줄러에서 유사하게 `prometheusClient` 직접 사용)
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java:28` — `TTL=1시간` (4번 제안 쿨다운 기준 참고)
- `aiops/src/main/java/aiops/aiops/AiopsApplication.java` — `@EnableScheduling` 추가 대상

## 외부 참조
- 직전 작업 설계 기록: `docs/design-notes.md`의 "카나리(v1/v2) 배포 구조" 섹션 (커밋 306b9ac)
