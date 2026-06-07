# 체크리스트: Pyroscope(Continuous Profiler) 도입 및 aiops 통합

- 마지막 업데이트: 2026-06-07

## 진행 상황
- [x] 단계 1: Pyroscope 서버 helm 템플릿 + Grafana 데이터소스 등록
  - [x] helm/promotion-monitoring/templates/traces/pyroscope.yaml 신규 (Tempo/Loki 패턴)
  - [x] values.yaml에 pyroscope 블록 추가 (port 4040, image grafana/pyroscope:latest)
  - [x] datasource.yml에 Pyroscope 데이터소스 등록 + Tempo↔Pyroscope tracesToProfiles 연동 추가
  - [x] grafana.yaml에 GF_INSTALL_PLUGINS=grafana-pyroscope-datasource 추가 (Grafana 10.0.3 미내장 플러그인)
  - [x] helm template promotion-monitoring 렌더링 통과 (exit=0, Service+Deployment+datasource 정상 출력)
- [x] 단계 2: 파일럿 서비스(server-a/b/c) Java agent 연동
  - [x] values.yaml: serverA/B/C에 profiler.enabled: true 추가
  - [x] deployment.yaml(server-a/b/c): initContainer(curl로 pyroscope-java agent jar 다운로드) +
        emptyDir 공유볼륨 + JAVA_TOOL_OPTIONS/-javaagent + PYROSCOPE_* env + volumeMounts/volumes 추가
        (모두 profiler.enabled 플래그로 토글, Dockerfile 변경 없음)
  - [x] helm template promotion-app 렌더링 통과 (exit=0, 3개 서비스 모두 initContainers·env·volumes 정상 출력)
  - [x] 파일럿 검증 완료 후 동일 패턴으로 gateway/user-service까지 확장 적용
        (values.yaml profiler.enabled: true + deployment.yaml initContainer/env/volumes,
        plan.md "비범위" 항목 갱신 — 사용자 결정에 따른 의도된 확장)
- [x] 단계 3: 로컬 docker-compose Pyroscope 추가 및 E2E 검증
  - [x] (계획 수정: docker-compose.monitoring.yml이 아니라 실제 로컬 스택이 정의된
        docker-compose.yml에 추가하는 것으로 변경 — 사용자 확인 완료. override.yml 미변경)
  - [x] docker-compose.yml에 pyroscope 서버 컨테이너 + pyroscope-agent-init
        (curl로 agent jar를 named volume에 다운로드하는 1회성 컨테이너, Helm
        initContainer 패턴과 동일 구조) 추가, pyroscope-agent named volume 등록
  - [x] server-a에 volume mount(/agent) + JAVA_TOOL_OPTIONS=-javaagent + PYROSCOPE_* env 추가
  - [x] docker compose config 검증 통과
  - [x] 로컬 E2E: server-a 기동 후 Pyroscope 쿼리 API에서 server-a 플레임그래프
        데이터(실제 메서드 호출 스택 포함) 확인 완료
- [x] 단계 4: aiops ObservabilityTools — queryProfilerHotspots 추가
  - [x] application.yaml: observability.pyroscope.url 설정 추가 (PYROSCOPE_URL env, 기본값 http://pyroscope:4040)
  - [x] RestClientConfig: pyroscopeClient 빈 추가 (기존 tempoClient 패턴과 동일)
  - [x] ObservabilityTools: queryProfilerHotspots(@Tool) + extractHotspots 핫스팟 파싱 헬퍼 추가
        (Pyroscope render API의 flamebearer levels[offset,total,self,nameIndex] 구조에서
        self 시간을 nameIndex별 합산 → 비율 정렬 → 상위 N개 반환. 실제 라이브 데이터로
        파싱 로직 검증 완료)
  - [x] .\gradlew.bat :aiops:compileJava BUILD SUCCESSFUL
- [x] 단계 5: AiOpsAgentService 프롬프트 — 새 도구 호출 케이스 명시
  - [x] SYSTEM_PROMPT에 6-1 단계 추가: Tempo 트레이스에서 지연 서비스는 식별됐으나
        내부 코드 원인이 불분명할 때 queryProfilerHotspots 호출 + *원인* 섹션에
        구체 메서드명·비율 포함하도록 명시. profiler 데이터 없을 시 스킵 조건도 명시.
  - [x] queryProfilerHotspots는 기존 ObservabilityTools에 추가됐으므로 .tools(observabilityTools, ...)
        등록만으로 자동 인식 (생성자/등록 코드 변경 불필요)
  - [x] .\gradlew.bat :aiops:compileJava BUILD SUCCESSFUL
- [x] 단계 6: 통합 검증
  - [x] gradle build / helm template / docker-compose E2E 종합 확인

## 최종 검증
- [x] .\gradlew.bat :aiops:build 통과 (BUILD SUCCESSFUL)
- [x] helm template (promotion-monitoring, promotion-app) 렌더링 오류 없음 (둘 다 OK)
- [x] 로컬 docker-compose E2E로 Pyroscope 데이터 수집 확인
      (server-a 실제 라이브 프로파일 데이터로 extractHotspots와 동일한 파싱 로직을
      재현해 정상적인 핫스팟 목록 — libc/libjvm 네이티브 프레임 등 — 출력 확인)
- [x] plan.md "비범위" 침범 없음 확인 (git diff --stat로 확인, 계획된 파일만 변경됨)
- [x] git diff --stat로 변경 범위 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (작업 중 기록 예정)

---

# [진행 중] 체크리스트: aiops 알람→프로파일러→Slack E2E 라이브 테스트 (Docker Desktop K8s)

- 마지막 업데이트: 2026-06-07

## 진행 상황
- [ ] 단계 1: Helm으로 Docker Desktop K8s에 최소 구성 배포
  - [ ] kubectl create namespace promotion
  - [ ] Istio 리소스(VirtualService/DestinationRule) 필터링 후 kubectl apply
  - [ ] kubectl get pods -n promotion 모두 Running
- [ ] 단계 2: Pyroscope 호스트 노출 + docker-compose 연동 설정
  - [ ] kubectl port-forward로 Pyroscope(4040) 호스트 노출
  - [ ] override.yml에 PYROSCOPE_URL=http://host.docker.internal:<port> 추가
  - [ ] aiops 컨테이너에서 curl로 Pyroscope API 응답 확인
- [ ] 단계 3: docker-compose 스택(aiops 포함) 기동 + 시크릿 연동 확인
  - [ ] docker compose up, docker logs aiops 정상 기동 확인
- [x] 단계 4: K8s server-a 트래픽 발생 → 프로파일 데이터 수집 확인
  - [x] /pyroscope/render API에서 server-a self-time 데이터 확인
        (numTicks=67480000000, flamebearer levels 파싱 결과 libjvm/libc 네이티브 프레임 +
        애플리케이션 프레임(HqlParser 등) 포함된 실제 라이브 데이터 확인)
- [ ] 단계 5: 알람 → aiops → 프로파일러 조회 → Slack 보고서 E2E
  - [ ] curl로 Alertmanager 포맷 알람 POST → aiops 로그에서 도구 호출 체인 확인
  - [ ] 실제 Slack 채널에서 핫스팟 정보 포함 보고서 수신 확인 (육안)
- [ ] 단계 6: 정리
  - [ ] port-forward 종료, docker compose down, helm uninstall / ns 삭제

## 최종 검증
- [ ] 성공 기준 6개 모두 충족 확인 (plan.md 참고)
- [ ] git status로 override.yml이 추적 대상 아님 + 의도치 않은 변경 없음 확인
- [ ] plan.md "비범위" 침범 없음 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (작업 중 기록 예정)
