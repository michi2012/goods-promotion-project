# 맥락 노트: AIOps 추가 K8s 도구 및 Helm 롤백 구현

## 왜 이 방식을 선택했는가

### proposeScale 제거 → proposeHpaPatch 통합
server-a/b/c 모두 HPA가 활성화되어 있어 `kubectl scale --replicas=N`은 HPA가 15~30초 후 오버라이드함.
따라서 `kubectl patch hpa --patch '{"spec":{"maxReplicas":N}}'`이 올바른 접근.
gateway도 트래픽 진입점으로 HPA 추가가 맞고, 이로써 모든 스케일 가능 서비스가 proposeHpaPatch로 통일됨.

### aiops에 HPA 미적용
aiops가 2개 이상 뜨면 같은 알람을 두 pod가 동시에 분석 → Slack 중복 메시지 발생.
deduplication이 in-memory라 인스턴스 간 공유 불가. 단일 인스턴스 유지.

### helm rollback vs kubectl rollout undo
Helm 관리 환경에서 `kubectl rollout undo`는 Deployment만 되돌리고 Helm release 상태(ConfigMap, Secret 등)는 그대로 남음.
`helm rollback promotion-app`이 Helm이 관리하는 모든 리소스를 이전 revision으로 통째로 되돌려 일관성 보장.
단점은 서비스 선택적 롤백 불가(전체 롤백)지만, 현재 promotion-app이 단일 release이므로 이게 최선.

### Helm release 단위 (현재: 단일 promotion-app)
프로덕션 MSA 표준은 서비스별 분리 release이나, 현재 차트 구조 전환은 별도 대규모 작업.
proposeHelmRollback은 promotion-app 전체 롤백으로 구현하고, Slack 메시지에 "전체 release 롤백" 명시.
추후 차트 분리 시 releaseName을 파라미터로 받는 구조라 확장 가능.

### Kafka 컨슈머 대상
server-a(Saga 오케스트레이터), server-b(CQRS 읽기), server-c(결제, Kafka 소비 전용) 모두 Kafka 컨슈머.
AI가 알람의 consumergroup 라벨에서 서비스명을 추론하여 해당 HPA에 proposeHpaPatch 호출.

## 검토했으나 채택하지 않은 대안

### 대안 A: proposeScale 유지 (kubectl scale)
- 무엇: 기존 proposeScale을 그대로 두고 HPA 없는 서비스에만 사용
- 왜 안 썼나: gateway에 HPA를 추가함으로써 모든 스케일 가능 서비스가 HPA를 가지게 됨. proposeScale 유지 이유 없어짐.

### 대안 B: 서비스별 분리 Helm release
- 무엇: promotion-app을 server-a, server-b, server-c 개별 Helm chart로 분리
- 왜 안 썼나: 차트 구조 전면 개편 + CI/CD 파이프라인 분리 필요. 이번 작업 범위 초과.

### 대안 C: aiops HPA 적용
- 무엇: aiops도 HPA 붙여서 스케일 아웃
- 왜 안 썼나: in-memory deduplication으로 인한 중복 알람 처리 문제. 해결하려면 Redis 기반 분산 lock 필요 — 별도 작업.

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` — K8s 도구 메서드 모음
- `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — 승인/실행 로직
- `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java` — Slack 버튼 콜백
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java` — 시스템 프롬프트
- `helm/promotion-app/templates/gateway/hpa.yaml` — gateway HPA (신규)
- `helm/promotion-monitoring/files/alert-rules.yml` — KafkaConsumerLagHigh 알람
