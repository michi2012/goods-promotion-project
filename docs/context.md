# 맥락 노트: Pyroscope(Continuous Profiler) 도입 및 aiops 통합

## 왜 이 방식을 선택했는가
- Tempo(분산 트레이싱)는 "어느 서비스/어느 호출이 느린가"까지는 짚어주지만,
  span 경계 안쪽에서 무슨 일이 있었는지(락 경합, GC, 직렬화, 정규식 컴파일 등)는
  보여주지 못한다. 이 간극을 메우기 위해 continuous profiler(Pyroscope) 도입을 결정.
- Java agent 방식 채택: eBPF 기반은 JVM 메서드명 심볼리케이션이 까다로워
  "코드 라인까지 짚는다"는 목표에 부적합. 사용자가 직접 "Java agent" 선택.
- initContainer로 agent jar를 받는 방식 채택: Dockerfile/이미지 빌드 파이프라인을
  건드리지 않아 블라스트 반경을 최소화 (사용자 선택, "Dockerfile 수정 = 이미지
  빌드 영향권"이라는 점을 고려).
- 파일럿(server-a/b/c)부터 적용: 6개 서비스 전체 동시 적용 시 문제 발생 시
  원인 추적이 어려움. 사용자가 server-a/b/c를 직접 지정.
- 로컬 docker-compose에도 Pyroscope를 추가해 검증: CLAUDE.md "우회 수단이
  있으면 구성해서 검증" 원칙 — Pyroscope는 로컬 컨테이너 구동이 가능하므로
  "진짜 로컬 불가" 사유에 해당하지 않음. 사용자가 직접 선택.

## 검토했으나 채택하지 않은 대안
### 대안 A: eBPF 기반 DaemonSet 프로파일러
- 무엇: 앱 이미지/코드 변경 없이 클러스터 전체에 1회 설치하는 방식
- 왜 안 썼나: JVM 메서드명 심볼리케이션이 추가 설정 없이는 흐릿해서,
  "코드 레벨 핫스팟"을 짚으려는 목표와 맞지 않음

### 대안 B: JFR(Java Flight Recorder)만 사용
- 무엇: JVM 내장 프로파일링, 별도 서버 불필요
- 왜 안 썼나: HTTP 쿼리 API가 없어 aiops가 프로그래밍 방식으로 조회하기
  어려움 (파일 기반 덤프라 통합 난이도 ↑)

### 대안 C: Dockerfile에 agent jar 포함해 이미지 재빌드
- 무엇: 서비스 이미지 자체에 agent jar를 번들
- 왜 안 썼나: 이미지 빌드·푸시 파이프라인에 영향 — initContainer 방식이
  동일 효과를 더 적은 블라스트 반경으로 달성 가능 (사용자 선택)

## 기존 코드베이스 컨벤션
- 모니터링 컴포넌트: helm/promotion-monitoring/templates/{logs,metrics,traces,
  visualization}/ 하위에 Deployment+Service 템플릿, values.yaml에 설정 블록,
  files/datasource.yml에 Grafana 데이터소스 등록 (Tempo/Loki 패턴 참고)
- aiops 도구: aiops/src/main/java/aiops/aiops/tools/{ObservabilityTools,
  KubernetesTools}.java — @Tool 어노테이션 + 한국어 description으로 호출
  시점을 명시하는 컨벤션
- 시스템 프롬프트: aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java의
  SYSTEM_PROMPT 상수 — 절차 단계와 보고서 형식을 정의
- 로컬 docker-compose: docker-compose.monitoring.yml에 모니터링 스택 정의,
  docker-compose.msa.yml에 서비스 정의, override.yml로 로컬 전용 오버라이드

## 관련 파일/위치
- helm/promotion-monitoring/templates/traces/tempo.yaml — Pyroscope 템플릿
  작성 시 참고할 기존 패턴
- helm/promotion-monitoring/files/datasource.yml — 데이터소스 등록 위치
- helm/promotion-app/templates/server-a/deployment.yaml — agent 연동 시
  수정할 deployment 템플릿 패턴 (server-b/c도 동일 패턴)
- aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java — 새 도구 추가 위치
- aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java — SYSTEM_PROMPT 수정 위치
- docker-compose.monitoring.yml — 로컬 모니터링 스택 (Tempo/Loki가 정의된 곳)

## 외부 참조
- Pyroscope 공식 문서 (Java agent 연동, HTTP 쿼리 API) — 구현 단계에서 정확한
  엔드포인트·파라미터 확인 필요
- Grafana Pyroscope 데이터소스 플러그인 호환성 — Grafana 10.0.3 기준 확인 필요

---

# [진행 중] 맥락 노트: aiops 알람→프로파일러→Slack E2E 라이브 테스트 (Docker Desktop K8s)

## 왜 이 방식을 선택했는가
- 처음에는 promotion-app 전체를 Helm으로 K8s에 올려서 검증하는 방향을 검토했으나,
  사용자가 "이미 docker-compose(infra/monitoring/msa)로 Pyroscope+server-a
  프로파일러가 동작하는 로컬 스택이 있는데 왜 새로 복잡하게 구성하냐"고 지적함.
  → 기존에 검증된 docker-compose 스택(aiops 포함)을 그대로 재사용하고,
    "Helm 배포가 실제로 동작하는가"는 Pyroscope+server-a만 K8s에 띄워 확인하는
    것으로 범위를 좁힘.
- 두 검증 목표(① aiops E2E, ② Helm 배포 동작 확인)를 하나의 시나리오로 통합:
  docker-compose의 aiops가 Helm으로 K8s에 배포한 Pyroscope를 실제로 조회하도록
  연결하면, "Helm 배포물이 정상 동작하는가"와 "aiops가 그 데이터를 활용해
  보고서를 쓰는가"를 한 번의 E2E로 같이 검증할 수 있음. 사용자가 이 통합
  시나리오에 동의함.
- `docker-compose.override.yml`에 이미 `.kube` 디렉터리 마운트 +
  `host.docker.internal` 패턴(PROMETHEUS_URL/LOKI_URL/TEMPO_URL)이 구성되어 있음을
  발견 — 사용자가 사전에 "docker-compose의 aiops가 로컬 K8s를 kubectl로 조작하게
  하려고" 준비해둔 것. 이 기존 설정 패턴을 그대로 따라 PYROSCOPE_URL도
  `host.docker.internal` 경유로 연결하는 것이 일관성 있는 선택.
- 알람 트리거는 Prometheus 알람 규칙의 실제 임계치 발동을 기다리는 대신
  Alertmanager 포맷 JSON을 aiops `/webhook/prometheus`에 직접 curl POST하는
  방식 채택 — 검증 대상은 "aiops가 알람을 받아 도구를 호출하고 보고서를
  작성·발송하는 파이프라인"이지 "Prometheus 규칙이 맞게 설정됐는가"가 아니므로,
  synthetic payload로도 목적 달성 가능 (Simplicity First).

## 검토했으나 채택하지 않은 대안
### 대안 A: promotion-app 전체를 Helm으로 K8s에 배포 (Istio 포함)
- 무엇: gateway/user-service/server-a/b/c 전체 메시 + Istio + 의존 인프라를
  Docker Desktop K8s에 모두 배포해서 검증
- 왜 안 썼나: 로컬 자원 부담이 매우 크고, Istio가 로컬에 미설치 상태(사용자 확인).
  이번 검증 목적(알람→프로파일러→Slack 흐름, Helm 배포 정상 동작)에는 과한 범위.

### 대안 B: aiops도 K8s에 Helm으로 배포해서 완전한 K8s 환경에서 검증
- 무엇: docker-compose 대신 aiops까지 K8s에 올려서 "운영과 동일한 환경"으로 검증
- 왜 안 썼나: 이미 docker-compose의 aiops가 Pyroscope 연동·Slack·Gemini 키
  주입까지 검증된 상태(이전 [완료] 작업)이고, override.yml에 K8s 조작용 설정까지
  준비되어 있음. 굳이 다시 K8s에 올려 처음부터 디버깅할 이유가 없음 (사용자 지적).

### 대안 C: Prometheus 알람 규칙을 실제로 발동시켜 알람 전파 경로 전체를 검증
- 무엇: 부하를 걸어 실제 임계치를 넘기고 Prometheus → Alertmanager → aiops
  전체 경로를 통째로 검증
- 왜 안 썼나: 이번 검증의 핵심은 "aiops가 알람을 받은 *이후*"의 흐름(도구 호출,
  프로파일러 조회, Slack 발송)이지 알람 규칙 자체의 정확성이 아님. synthetic
  POST로 동일한 입력을 만들어 핵심 경로만 빠르게 검증 (Simplicity First).

## 기존 코드베이스 컨벤션
- `docker-compose.override.yml`: 로컬 전용 시크릿/엔드포인트 오버라이드,
  `.gitignore` 처리됨 (line 48). `host.docker.internal` 패턴으로 호스트에 노출된
  서비스를 컨테이너에서 참조하는 기존 컨벤션 확인.
- aiops 알람 수신: `aiops/src/main/java/aiops/aiops/webhook/PrometheusWebhookController.java`
  — `POST /webhook/prometheus`에서 Alertmanager 포맷 JSON 수신.
- Alertmanager → aiops 라우팅: `helm/promotion-monitoring/files/alertmanager.yml`
  — receiver `aiops-agent`가 `http://aiops:8085/webhook/prometheus`로 전달.

## 관련 파일/위치
- docker-compose.override.yml — 시크릿 주입 + host.docker.internal 연동 설정 위치
  (이번 세션에서 Gemini API 키 교체 완료, Slack webhook은 기존 값과 동일하여 유지)
- aiops/src/main/java/aiops/aiops/webhook/PrometheusWebhookController.java —
  알람 payload 수신 엔드포인트 (synthetic curl POST 대상)
- helm/promotion-app/templates/{discovery,server-a}/ — Helm 배포 대상
  (server-a는 profiler 연동 완료 상태, MySQL/Redis/Kafka 의존)
- helm/promotion-monitoring/templates/traces/pyroscope.yaml — Helm 배포 대상

## 외부 참조
- (해당 없음 — 기존 검증된 패턴/코드 재사용 위주)
