# 계획서: AIOps 추가 K8s 도구 및 Helm 롤백 구현

- 작성일: 2026-06-05

## 목표
proposeHpaPatch(HPA maxReplicas 조정), Kafka 컨슈머 랙 대응(proposeScale → proposeHpaPatch 재활용),
proposeHelmRollback(배포 후 에러율 급증 시 helm rollback 제안) 세 가지를 추가하여 AIOps K8s 자동화 도구를 완성한다.
gateway에도 HPA를 추가하여 모든 스케일 가능 서비스를 proposeHpaPatch로 통일한다.
기존 proposeScale(kubectl scale)은 제거한다.

## 성공 기준
- [ ] KubeHPAAtMaxReplicas 알람 → AI가 proposeHpaPatch 호출 → Slack 버튼 발송 → 승인 → kubectl patch hpa 실행 확인
- [ ] KafkaConsumerLagHigh 알람 → AI가 해당 컨슈머 서비스에 proposeHpaPatch 호출 → Slack 버튼 발송 확인
- [ ] 에러율 급증 + 최근 배포 감지 → AI가 proposeHelmRollback 호출 → Slack 버튼 → helm rollback promotion-app 실행 확인
- [ ] helm binary가 aiops Dockerfile에 설치되어 있음
- [ ] gateway HPA가 helm 차트에 추가되어 있음
- [ ] 기존 proposeScale 코드가 제거되어 있음

## 비범위 (Out of Scope)
- aiops HPA 추가 — 다중 인스턴스 시 중복 알람 처리 문제로 제외
- proposeMemoryLimitPatch — deployment spec 변경은 PR로 처리하는 게 맞음
- Node cordon/drain — 위험도 높아 자동화 제외
- Helm release 서비스별 분리 — 차트 구조 전면 개편 필요, 별도 작업으로 분리
- Slack App signing secret 검증 — 보안 강화는 추후

## 단계별 작업 계획

### 단계 1: Dockerfile에 helm 설치 + proposeScale 제거
- 변경 파일:
  - `aiops/Dockerfile` — helm v3.15.0 설치 레이어 추가
  - `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` — proposeScale 메서드 제거, buildScaleBlocks 제거
  - `aiops/src/main/java/aiops/aiops/approval/ActionApprovalService.java` — executeScale 제거
  - `aiops/src/main/java/aiops/aiops/slack/SlackInteractiveController.java` — approve_scale, reject_scale 핸들러 제거
- 검증: `.\gradlew.bat :aiops:compileJava -x test`
- 롤백: git revert
- 예상 소요: 짧음

### 단계 2: proposeHpaPatch 도구 구현
- 변경 파일:
  - `KubernetesTools.java` — `@Tool proposeHpaPatch(String hpaName, int newMaxReplicas, String reason)` 추가, buildHpaPatchBlocks 추가
  - `ActionApprovalService.java` — `executeHpaPatch` 추가 (`kubectl patch hpa {name} -n {ns} -p '{"spec":{"maxReplicas":N}}'`)
  - `SlackInteractiveController.java` — `approve_hpa_patch`, `reject_hpa_patch` 핸들러 추가
- 검증: KubeHPAAtMaxReplicas 알람 전송 → Slack 버튼 확인 → 승인 → 로그에서 kubectl patch 실행 확인
- 롤백: 메서드 제거
- 예상 소요: 보통

### 단계 3: proposeHelmRollback 도구 구현
- 변경 파일:
  - `KubernetesTools.java` — `@Tool proposeHelmRollback(String releaseName, String reason)` 추가, buildHelmRollbackBlocks 추가
  - `ActionApprovalService.java` — `executeHelmRollback` 추가 (`helm rollback {release} -n {ns}`)
  - `SlackInteractiveController.java` — `approve_helm_rollback`, `reject_helm_rollback` 핸들러 추가
- 검증: 알람 전송(배포 이력 + 에러율 급증 시나리오) → Slack 버튼 → 승인 → helm rollback 로그 확인
- 롤백: 메서드 제거
- 예상 소요: 보통

### 단계 4: gateway HPA 추가
- 변경 파일:
  - `helm/promotion-app/templates/gateway/hpa.yaml` (NEW)
  - `helm/promotion-app/values.yaml` — gateway.autoscaling 섹션 추가
- 변경 내용: gateway HPA (minReplicas:1, maxReplicas:3, CPU 60%)
- 검증: `helm template promotion-app ./helm/promotion-app | grep -A5 "HorizontalPodAutoscaler"`
- 롤백: hpa.yaml 삭제, values.yaml 원복
- 예상 소요: 짧음

### 단계 5: alert-rules.yml KafkaConsumerLagHigh 추가
- 변경 파일: `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용: `kafka_consumergroup_lag > 1000` (5분 지속) P2 알람 추가
- 검증: YAML 문법 확인
- 롤백: 알람 규칙 제거
- 예상 소요: 짧음

### 단계 6: 시스템 프롬프트 업데이트
- 변경 파일: `AiOpsAgentService.java`
- 변경 내용:
  - HPA at max → proposeHpaPatch 호출 조건 (proposeScale 조건 대체)
  - step 7 배포 이력 + 에러율 급증 → proposeHelmRollback 호출 조건 추가
  - KafkaConsumerLagHigh → 해당 컨슈머 deployment의 HPA에 proposeHpaPatch 호출 조건 추가
- 검증: 각 시나리오 알람 테스트
- 롤백: 프롬프트 이전 버전 복원
- 예상 소요: 짧음

### 단계 7: 재빌드 및 통합 테스트
- 검증: `gradlew :aiops:bootJar` → `docker compose build aiops` → 시나리오별 알람 전송
- 예상 소요: 보통

## 리스크 및 대응
- helm rollback은 promotion-app 전체 롤백 → 서비스별 선택 불가. Slack 버튼 메시지에 "전체 release 롤백" 명시
- Kafka lag 메트릭명이 다를 수 있음 → kafka-exporter 메트릭 먼저 확인 후 alertname 조정
- helm binary 버전 호환성 → v3.15.0 고정

## 의존성
- helm binary: aiops 컨테이너에 설치
- kafka-exporter: 이미 promotion-monitoring에 존재
- Helm release 이름: promotion-app (Chart.yaml 기준)
