# 체크리스트: AIOps 에이전트 고도화 (5개 영역)

- 마지막 업데이트: 2026-05-26

## 진행 상황

- [x] 단계 1: 중복 알람 억제 + 분석 실패 시 Slack 알림
  - [x] AlertDeduplicationService.java 신규 생성
  - [x] AiOpsAgentService.java — deduplication 주입 + catch 블록 Slack 알림 추가
  - [x] 검증 통과 (`.\gradlew.bat :mcp:compileJava` BUILD SUCCESSFUL)

- [x] 단계 2: DB 진단 도구 + GitHub 배포 이력 도구
  - [x] ObservabilityTools.java — callPrometheus() private 추출, queryDatabaseHealth() 추가, queryRecentCommits() + extractCommitSummaries() 추가
  - [x] RestClientConfig.java — githubClient 빈 추가
  - [x] application.yaml — github.owner, github.repo 추가
  - [x] 검증 통과 (`.\gradlew.bat :mcp:compileJava` BUILD SUCCESSFUL)

- [x] 단계 3: 연쇄 장애 추론 + 배포 이력 조회 프롬프트 개선
  - [x] AiOpsAgentService.java SYSTEM_PROMPT — queryDatabaseHealth 호출 단계 추가
  - [x] SYSTEM_PROMPT — queryRecentCommits 호출 및 배포 상관관계 분석 단계 추가
  - [x] SYSTEM_PROMPT — Kafka→CDC→API 연쇄 장애 추론 지시 추가
  - [x] 검증 통과 (내용 직접 확인)

- [x] 단계 4: 인간 승인 게이트 인프라
  - [x] ActionApprovalService.java 신규 생성
  - [x] ActionApprovalController.java 신규 생성 (POST /action/approve/{id})
  - [x] ObservabilityTools.java — proposeAction() 도구 추가
  - [x] 검증 통과 (`.\gradlew.bat :mcp:compileJava` BUILD SUCCESSFUL)

## 최종 검증
- [x] 모든 단계 컴파일 통과
- [x] plan.md 비범위 침범 없음 확인 (새 Gradle 의존성 없음, Slack App 전환 없음, JDBC 없음)
- [x] `git diff --stat`으로 변경 파일 최종 확인 (7개 파일, 신규 3개)

## 발견 사항 (작업 중 별도 처리 필요한 것)
-
