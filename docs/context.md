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
