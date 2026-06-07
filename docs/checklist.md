# 체크리스트: Pyroscope(Continuous Profiler) 도입 및 aiops 통합

- 마지막 업데이트: 2026-06-07

## 진행 상황
- [ ] 단계 1: Pyroscope 서버 helm 템플릿 + Grafana 데이터소스 등록
  - [ ] helm template promotion-monitoring 렌더링 통과 (육안 확인)
- [ ] 단계 2: 파일럿 서비스(server-a/b/c) Java agent 연동
  - [ ] helm template promotion-app 렌더링 통과 (육안 확인)
- [ ] 단계 3: 로컬 docker-compose Pyroscope 추가 및 E2E 검증
  - [ ] Pyroscope UI에서 server-a 프로파일 데이터 확인 (로컬 E2E)
- [ ] 단계 4: aiops ObservabilityTools — queryProfilerHotspots 추가
  - [ ] .\gradlew.bat :aiops:compileJava BUILD SUCCESSFUL
- [ ] 단계 5: AiOpsAgentService 프롬프트 — 새 도구 호출 케이스 명시
  - [ ] .\gradlew.bat :aiops:compileJava BUILD SUCCESSFUL
- [ ] 단계 6: 통합 검증
  - [ ] gradle build / helm template / docker-compose E2E 종합 확인

## 최종 검증
- [ ] .\gradlew.bat :aiops:build 통과
- [ ] helm template (promotion-monitoring, promotion-app) 렌더링 오류 없음
- [ ] 로컬 docker-compose E2E로 Pyroscope 데이터 수집 확인
- [ ] plan.md "비범위" 침범 없음 확인
- [ ] git diff --stat로 변경 범위 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (작업 중 기록 예정)
