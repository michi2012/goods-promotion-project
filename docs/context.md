# 맥락 노트: AIOps 도구 추가 — getIstioMeshStatus / queryKafkaLag / 트래픽 가중치 검증

## 왜 이 방식을 선택했는가

**getIstioMeshStatus**: `proposeTrafficShift`가 현재 v1/v2 가중치를 모른 채 제안을 생성하는 맹점이 있었음.
kubectl로 VirtualService + DestinationRule을 `-o yaml`로 읽으면 AI가 현재 상태를 파악한 뒤 적절한 가중치를 제안 가능.
read-only이므로 Slack 승인 불필요 — `getClusterStatus()` 패턴 그대로 적용.

**queryKafkaLag**: AiOpsAgentService 시스템 프롬프트에 "KafkaConsumerLagHigh 알람 시 queryPrometheusMetrics로 kafka_consumergroup_lag를 조회하라"고 명시되어 있으나,
AI가 매번 PromQL을 직접 작성해야 해 오류 위험이 있었음. 전용 도구로 고정 쿼리를 제공하면 일관성 보장.
kafka-exporter(danielqsj/kafka-exporter)가 사용 중임을 docker-compose.yml + alert-rules.yml에서 확인.

**v1+v2 검증**: AI가 v1=60, v2=60 같은 잘못된 합계를 줄 경우 kubectl patch 실행 시까지 오류가 미뤄짐.
Slack 승인 후 실행 단계(ActionApprovalService)에서 잡을 수 있지만, 도구 레벨에서 즉시 에러 String 반환이 AI 피드백 루프에 유리.

## 검토했으나 채택하지 않은 대안

### getIstioMeshStatus — JSON 출력
- `-o json`: 구조화 데이터이지만 field 수가 많아 토큰 낭비
- yaml이 AI 가독성과 토큰 균형 모두 유리

### queryKafkaLag — consumergroup 파라미터
- 특정 컨슈머그룹만 필터링하는 파라미터 추가 가능
- 현재 서비스가 server-c 단일 컨슈머그룹이므로 전체 조회로 충분. 파라미터 없는 단순 버전 채택.

### 검증 실패 — IllegalArgumentException
- 예외 대신 에러 String 반환: Tool Calling에서 예외는 프레임워크가 에러 메시지로 변환하지만,
  명시적 String 반환이 AI에게 더 명확한 피드백 제공. 기존 도구의 실패 처리 패턴과 일치.

## 기존 코드베이스 컨벤션
- 읽기 도구: 반환 타입 String, Slack 승인 없음 — `getClusterStatus()`, `queryPrometheusMetrics()` 참고
- 쓰기/조치 도구: `approvalService.propose()` + `slackService.sendBlockKit()` — `proposeTrafficShift()` 참고
- Prometheus 쿼리: `callPrometheus(promql)` + `truncateData(response, label)` 패턴
- kubectl 실행: `runKubectl(String... args)` — List 기반으로 커맨드 인젝션 방지

## 관련 파일/위치
- `aiops/src/main/java/aiops/aiops/tools/KubernetesTools.java` — getIstioMeshStatus, 검증 추가
- `aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java` — queryKafkaLag 추가
- `aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java` — 시스템 프롬프트에서 kafka_consumergroup_lag 확인
- `monitoring/prometheus/alert-rules.yml` — kafka_consumergroup_lag 메트릭명 확인
