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
- gateway, aiops, user-service로의 확장 (파일럿 검증 후 별도 작업으로 분리)
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
- 변경 파일: docker-compose.monitoring.yml, docker-compose.override.yml
- 변경 내용: Pyroscope 컨테이너 추가. server-a 컨테이너에 agent jar를 볼륨
  마운트 + JAVA_TOOL_OPTIONS 환경변수로 연동 (이미지 재빌드 없이 검증).
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
