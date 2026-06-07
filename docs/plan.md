# 계획서: Pyroscope(Continuous Profiler) 도입 및 aiops 통합

- 작성일: 2026-06-07

## 목표
JVM 기반 파일럿 서비스(server-a/b/c)에 continuous profiling(Pyroscope)을 도입해
Tempo가 짚어낸 "어느 서비스가 느린가"를 넘어 "그 서비스 내부 어느 메서드가
병목인가"까지 확인하고, aiops가 장애 분석 시 이 데이터를 조회해 보고서에
반영하도록 한다.

## 성공 기준
- [ ] Pyroscope 서버가 helm/promotion-monitoring에 배포되고 Grafana 데이터소스로
      등록됨 (helm template 렌더링 오류 없음, 육안 확인)
- [ ] server-a/b/c 파일럿 파드에 Java agent가 연동되어 Pyroscope UI에서 실제
      프로파일 데이터(플레임그래프) 확인 가능 (로컬 docker-compose E2E)
- [ ] aiops에 queryProfilerHotspots 도구가 추가되고
      .\gradlew.bat :aiops:compileJava BUILD SUCCESSFUL
- [ ] AiOpsAgentService SYSTEM_PROMPT에 새 도구 호출 케이스 명시
- [ ] helm template promotion-monitoring / promotion-app 렌더링 오류 없음

## 비범위 (Out of Scope)
- ~~gateway, aiops, user-service로의 확장 (파일럿 검증 후 별도 작업으로 분리)~~
  → server-a/b/c 파일럿 검증 완료 후 동일 패턴(initContainer + profiler.enabled 토글)으로
    gateway/user-service까지 확장 적용 완료 (사용자 결정, 의도된 확장)
- eBPF 기반 프로파일링 도입
- Dockerfile/이미지 빌드 파이프라인 변경 (initContainer 방식으로 우회)
- 기존 알람·HPA·트래픽 제어·롤백 로직 변경

## 단계별 작업 계획

### 단계 1: Pyroscope 서버 helm 템플릿 + Grafana 데이터소스 등록
- 변경 파일: helm/promotion-monitoring/values.yaml,
  helm/promotion-monitoring/templates/traces/pyroscope.yaml(신규),
  helm/promotion-monitoring/files/datasource.yml,
  helm/promotion-monitoring/templates/visualization/grafana.yaml(필요 시 플러그인 설치)
- 변경 내용: Tempo/Loki와 동일한 패턴(Deployment+Service+values 블록)으로
  Pyroscope 서버(grafana/pyroscope:latest, 4040 포트) 추가. Grafana datasource.yml에
  Pyroscope 데이터소스 등록 (필요 시 GF_INSTALL_PLUGINS로 플러그인 설치 옵션 추가).
- 검증 방법: helm template promotion-monitoring 렌더링 오류 없음 (육안 확인)
- 롤백 방법: git restore 해당 파일 / 신규 템플릿 파일 삭제
- 예상 소요: 보통

### 단계 2: 파일럿 서비스(server-a/b/c) Java agent 연동
- 변경 파일: helm/promotion-app/templates/server-a|b|c/deployment.yaml,
  helm/promotion-app/values.yaml
- 변경 내용: initContainer로 pyroscope-java agent jar를 emptyDir 공유 볼륨에
  다운로드 → 메인 컨테이너에서 JAVA_TOOL_OPTIONS=-javaagent:/agent/pyroscope.jar 주입,
  PYROSCOPE_APPLICATION_NAME / PYROSCOPE_SERVER_ADDRESS 환경변수 설정.
  profiler.enabled 플래그로 토글 가능하게 구성 (파일럿 한정, Dockerfile 변경 없음).
- 검증 방법: helm template promotion-app 렌더링 오류 없음 (육안 확인)
- 롤백 방법: git restore 해당 파일
- 예상 소요: 보통

### 단계 3: 로컬 docker-compose 환경에 Pyroscope 추가 및 E2E 검증
- 변경 파일: docker-compose.yml
  (※ 진행 중 확인 결과, 로컬 `docker compose up`이 실제로 사용하는 모니터링 스택
  (otel-collector/tempo/prometheus 등)은 docker-compose.monitoring.yml이 아니라
  docker-compose.yml에 직접 정의되어 있고 promotion-network를 사용함. 계획 수립 시
  착오로 monitoring.yml을 적었으나 실제로는 docker-compose.yml 수정이 맞음.
  override.yml은 시크릿(API 키, Slack 웹훅) 포함 파일이라 변경하지 않음 — 사용자 확인 완료)
- 변경 내용: Pyroscope 서버 컨테이너 + agent jar 다운로드용 1회성 init 서비스
  (named volume에 다운로드 후 종료, Helm initContainer 패턴과 동일 구조) 추가.
  server-a 컨테이너에 해당 volume 마운트 + JAVA_TOOL_OPTIONS 환경변수로 연동
  (이미지 재빌드·Dockerfile 변경 없이 검증).
  로컬 트래픽 발생 후 Pyroscope UI(4040)에서 실제 플레임그래프 데이터 확인.
- 검증 방법: docker compose up 후 Pyroscope UI에서 server-a 프로파일 데이터
  시각적 확인 (로컬 E2E, run_in_background로 기동)
- 롤백 방법: git restore / 컨테이너 제거
- 예상 소요: 보통

### 단계 4: aiops ObservabilityTools — queryProfilerHotspots 도구 추가
- 변경 파일: aiops/src/main/java/aiops/aiops/tools/ObservabilityTools.java
- 변경 내용: Pyroscope HTTP 쿼리 API를 호출해 지정 서비스·시간범위의 상위 N개
  핫스팟 메서드(self-time 기준)를 텍스트로 요약 반환하는 @Tool 메서드 추가.
- 검증 방법: .\gradlew.bat :aiops:compileJava BUILD SUCCESSFUL
- 롤백 방법: git restore 해당 파일
- 예상 소요: 보통

### 단계 5: AiOpsAgentService 프롬프트 — 새 도구 호출 케이스 명시
- 변경 파일: aiops/src/main/java/aiops/aiops/agent/AiOpsAgentService.java
- 변경 내용: SYSTEM_PROMPT에 "Tempo에서 특정 서비스의 응답 지연이 확인되고
  그 내부 원인이 불분명한 경우 queryProfilerHotspots를 호출해 핫스팟을
  확인하라"는 절차 추가. 보고서 *원인* 섹션에 핫스팟 정보를 포함하도록 명시.
- 검증 방법: .\gradlew.bat :aiops:compileJava BUILD SUCCESSFUL
- 롤백 방법: git restore 해당 파일
- 예상 소요: 짧음

### 단계 6: 통합 검증
- 변경 파일: 없음 (검증만)
- 변경 내용: gradle build, helm template(promotion-monitoring, promotion-app),
  로컬 docker-compose E2E 종합 확인
- 검증 방법: .\gradlew.bat :aiops:build / helm template / docker compose 로그·UI 확인
- 롤백 방법: 해당 없음
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: initContainer가 매 파드 시작 시 외부(GitHub releases)에서 jar
  다운로드 → 네트워크 장애 시 파드 시작 지연/실패 가능
  → 대응: 파일럿 단계에서는 우선 적용하고, 반복 문제 시 ECR에 agent jar를
    미리 올려두는 방식으로 전환 검토 (별도 작업으로 분리)
- 리스크: Grafana 10.0.3에 Pyroscope 데이터소스 플러그인이 기본 포함 안 될 수 있음
  → 대응: GF_INSTALL_PLUGINS 환경변수로 설치, 호환성 문제 시 사용자와 논의
- 리스크: 프로파일링 에이전트의 CPU/메모리 오버헤드 (통상 1~2% 수준)
  → 대응: profiler.enabled 플래그로 즉시 비활성화 가능하게 구성, 배포 후 모니터링

## 의존성
- Pyroscope 서버 (helm/promotion-monitoring 신규 컴포넌트)
- pyroscope-java agent (외부 jar, GitHub releases)
- Grafana 10.0.3 — Pyroscope 데이터소스 플러그인 호환성 확인 필요

---

## [진행 중] aiops 알람→프로파일러→Slack E2E 라이브 테스트 (Docker Desktop K8s)

- 작성일: 2026-06-07

### 목표
Helm으로 Docker Desktop K8s에 배포한 Pyroscope+profiler 연동 서비스(server-a)를
기존 docker-compose의 aiops가 실제로 조회하도록 연결하여, 알람 수신 → 프로파일러
핫스팟 분석 → Slack 보고서 발송까지의 전체 흐름을 실제 Gemini API + Slack
Webhook으로 E2E 검증한다 (Helm 배포 자체의 정상 동작 확인을 겸함).

### 성공 기준
- [ ] Helm으로 Docker Desktop K8s에 Pyroscope + server-a(profiler 연동) 정상 배포
      (`kubectl get pods -n promotion` 모두 Running)
- [ ] kubectl port-forward로 Pyroscope를 호스트에 노출하고, docker-compose의
      aiops가 `host.docker.internal` 경유로 정상 조회 가능
- [ ] K8s server-a에 트래픽 발생 후 Pyroscope에서 실제 프로파일 데이터 확인
      (query API 응답에 server-a self-time 데이터 존재)
- [ ] curl로 Alertmanager 포맷 알람을 aiops `/webhook/prometheus`에 POST →
      aiops 로그에서 queryProfilerHotspots 등 도구 호출 흐름 확인
- [ ] 실제 Slack 채널에 핫스팟 메서드명·비율이 포함된 보고서 수신 확인 (육안)
- [ ] 시크릿(Slack webhook, Gemini key)이 git에 커밋되지 않음
      (`docker-compose.override.yml` gitignore 상태 유지, `git status` 확인)

### 비범위 (Out of Scope)
- Istio 설치 및 검증 (사용자 확인 — 이번엔 미설치 상태로 진행, 관련 템플릿 제외)
- 전체 마이크로서비스 메시(gateway/user-service/server-b/c) 배포
- Prometheus 알람 규칙의 실제 임계치 발동 시나리오 (synthetic curl POST로 대체)
- 작업 종료 후 K8s 리소스 영구 유지 (검증 후 정리, 단계 6)

### 단계별 작업 계획

#### 단계 1: Helm으로 Docker Desktop K8s에 최소 구성 배포
- 변경 파일: 없음 (배포 명령만, 차트 미변경 — 기존 helm 차트 그대로 사용)
- 변경 내용: `kubectl create namespace promotion` 후 promotion-app(discovery-service,
  server-a + 의존 인프라 MySQL/Redis/Kafka) + promotion-monitoring(Pyroscope) 배포.
  Istio 미설치 상태이므로 VirtualService/DestinationRule(`kind`)은 `helm template`
  렌더링 결과에서 필터링하여 `kubectl apply`로 제외 (차트 파일은 수정하지 않음).
- 검증 방법: `kubectl get pods -n promotion` 모두 Running
- 롤백 방법: `kubectl delete ns promotion` / `helm uninstall`
- 예상 소요: 김

#### 단계 2: Pyroscope 호스트 노출 + docker-compose 연동 설정
- 변경 파일: docker-compose.override.yml (PYROSCOPE_URL 추가)
- 변경 내용: `kubectl port-forward`로 Pyroscope(4040)를 호스트에 노출하고,
  override.yml에 `PYROSCOPE_URL: http://host.docker.internal:<port>`를 추가하여
  docker-compose의 aiops가 K8s에 배포된 Pyroscope를 바라보도록 설정
  (기존 PROMETHEUS_URL/LOKI_URL/TEMPO_URL의 host.docker.internal 패턴과 동일).
- 검증 방법: aiops 컨테이너 내부에서 curl로 Pyroscope API 응답 확인
- 롤백 방법: override.yml에 추가한 라인 제거 / port-forward 프로세스 종료
- 예상 소요: 짧음

#### 단계 3: docker-compose 스택(aiops 포함) 기동 + 시크릿 연동 확인
- 변경 파일: 없음 (시크릿은 사전에 override.yml에 교체 완료)
- 변경 내용: `docker compose up`으로 aiops 등 기동. Eureka/Pyroscope/Slack/Gemini
  연동이 정상인지 로그로 확인.
- 검증 방법: `docker logs aiops`에서 정상 기동 로그 확인, 에러 없음
- 롤백 방법: `docker compose down`
- 예상 소요: 보통

#### 단계 4: K8s server-a 트래픽 발생 → 프로파일 데이터 수집 확인
- 변경 파일: 없음
- 변경 내용: K8s에 배포된 server-a에 curl 등으로 트래픽 발생시켜 Pyroscope
  query API에서 실제 플레임그래프(self-time) 데이터 확인.
- 검증 방법: `/pyroscope/render` API 응답에 server-a 데이터 존재 확인
- 롤백 방법: 해당 없음
- 예상 소요: 짧음

#### 단계 5: 알람 → aiops → 프로파일러 조회 → Slack 보고서 E2E
- 변경 파일: 없음 (테스트 payload는 임시 인라인 curl로 전송, 파일로 남기지 않음)
- 변경 내용: Alertmanager 포맷 JSON(alertname, status=firing, app=server-a 등)을
  aiops `/webhook/prometheus`에 curl POST. aiops 로그에서 도구 호출 체인
  (queryPrometheusMetrics → ... → queryProfilerHotspots) 추적. 실제 Slack
  채널에서 핫스팟 메서드명·비율 포함 보고서 수신 확인.
- 검증 방법: aiops 로그 + Slack 채널 육안 확인
- 롤백 방법: 해당 없음
- 예상 소요: 보통

#### 단계 6: 정리
- 변경 파일: 없음
- 변경 내용: port-forward 종료, `docker compose down`, K8s 리소스 정리
  (`helm uninstall` / `kubectl delete ns promotion`) — 사용자 확인 후 진행
- 검증 방법: `kubectl get pods -n promotion` 빈 결과, `docker ps` 깨끗
- 롤백 방법: 해당 없음
- 예상 소요: 짧음

### 리스크 및 대응
- 리스크: server-a 의존성(MySQL/Redis/Kafka)이 K8s에 없으면 파드가 CrashLoop
  → 대응: 단계 1에서 의존 인프라도 함께 배포(임시 컨테이너 또는 helm 차트 활용).
    범위가 과도하게 커지면 진행 중 사용자와 재논의 (예: profiler 검증용으로
    의존성 없는 서비스로 축소)
- 리스크: `helm template` 결과에서 Istio 리소스(`kind: VirtualService/DestinationRule`)
  필터링이 부정확하면 일부 리소스 누락/중복 적용
  → 대응: 적용 전 필터링된 리소스 목록을 출력해 육안 확인 후 apply
- 리스크: Windows Docker Desktop에서 `host.docker.internal` 동작 여부
  → 대응: 기본 지원되나, 안 될 경우 K8s 서비스의 NodePort/LoadBalancer IP를
    직접 사용하는 대안 검토
- 리스크: 시크릿 노출
  → 대응: override.yml은 이미 `.gitignore` 처리됨 (git status로 추적 제외 확인됨),
    curl payload·로그에 시크릿 값 포함하지 않음

### 의존성
- Docker Desktop Kubernetes 활성화 상태
- 기존 helm 차트 (Pyroscope 적용 완료 — 위 [완료] 작업의 산출물)
- 사용자 제공 라이브 시크릿: Slack webhook, Gemini API 키
  (override.yml에 이미 반영 완료 — 이번 세션에서 Gemini 키 교체함)
- override.yml의 `.kube` 디렉터리 마운트 (사전 구성됨, aiops가 로컬 K8s를
  kubectl로 직접 조작 가능)
