# 계획서: AIOps 도구 추가 — getIstioMeshStatus / queryKafkaLag / 트래픽 가중치 검증

- 작성일: 2026-06-07

## 목표
AIOps 에이전트가 현재 Istio 메시 상태를 직접 조회하고, Kafka consumer lag를 정확한 수치로 확인할 수 있도록 두 도구를 추가한다.
또한 트래픽 시프트 제안 시 v1+v2 합이 100이 아닌 잘못된 입력을 사전 차단한다.

## 성공 기준
- [ ] `getIstioMeshStatus()` 추가 — kubectl로 VirtualService + DestinationRule yaml 반환 (KubernetesTools.java)
- [ ] `proposeTrafficShift()` 검증 — v1Weight + v2Weight ≠ 100이면 에러 String 반환, propose 미호출 (KubernetesTools.java)
- [ ] `queryKafkaLag()` 추가 — `kafka_consumergroup_lag` Prometheus 쿼리 반환 (ObservabilityTools.java)
- [ ] `gradle :aiops:compileJava` 오류 없음 (컴파일 통과)

## 비범위 (Out of Scope)
- AiOpsAgentService 시스템 프롬프트 수정 (별도 작업)
- ActionApprovalService / SlackInteractiveController 변경 없음
- 테스트 코드 (로컬 kubectl/Prometheus 없으므로 컴파일 통과로 대체)

## 단계별 작업 계획

### 단계 1: KubernetesTools.java — getIstioMeshStatus() 추가 + proposeTrafficShift() 검증
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java`
- 변경 내용:
  - `getIstioMeshStatus()`: `runKubectl("get", "virtualservice", "-n", namespace, "-o", "yaml")` +
    `runKubectl("get", "destinationrule", "-n", namespace, "-o", "yaml")` 조합해 반환
  - `proposeTrafficShift()` 맨 앞에 `v1Weight + v2Weight != 100` guard 추가, 위반 시 에러 String 반환
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: git checkout KubernetesTools.java
- 예상 소요: 짧음

### 단계 2: ObservabilityTools.java — queryKafkaLag() 추가
- 변경 파일: `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java`
- 변경 내용:
  - `queryKafkaLag()`: `callPrometheus("kafka_consumergroup_lag")` + `truncateData()` 패턴 적용
  - consumergroup, topic 레이블별 분류 반환
- 검증 방법: `.\gradlew.bat :aiops:compileJava`
- 롤백 방법: git checkout ObservabilityTools.java
- 예상 소요: 짧음

## 리스크 및 대응
- Kafka 메트릭명 불일치: `kafka_consumergroup_lag` — alert-rules.yml, AiOpsAgentService 시스템 프롬프트에서 동일 확인 완료
- getIstioMeshStatus yaml 출력 용량: `truncateData()` 없이 반환 → 필요 시 truncate 추가

## 의존성
- 기존 `runKubectl()`, `callPrometheus()`, `truncateData()` 메서드 재사용 (신규 메서드 없음)
- 신규 Gradle 의존성 없음
