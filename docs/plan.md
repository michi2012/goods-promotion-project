# 계획서: AIOps 에이전트 고도화 (5개 영역)

- 작성일: 2026-05-26

## 목표
현재 AIOps 에이전트(mcp 모듈)의 안정성·분석 품질·운영 안전성을 5개 영역에서 개선한다:
① 중복 알람 억제, ② 실패 시 Slack 알림, ③ DB·배포 진단 도구, ④ 연쇄 장애 프롬프트, ⑤ 인간 승인 게이트 인프라.

## 성공 기준
- [ ] 동일 alertname + labels fingerprint가 30분 이내 재수신될 경우 AI 호출 없이 스킵됨
- [ ] analyze() 내부 예외 발생 시 Slack에 실패 알림이 발송됨
- [ ] `queryDatabaseHealth()` 도구 호출 시 MySQL·Redis 핵심 지표가 Prometheus에서 조회됨
- [ ] `queryRecentCommits(minutes)` 호출 시 "시간 | 작성자 | 메시지" 줄글만 반환됨 (GitHub JSON 원본 미포함)
- [ ] `proposeAction()` 호출 시 UUID가 저장되고 `POST /action/approve/{id}` 엔드포인트가 응답함
- [ ] 모든 단계에서 `.\gradlew.bat :mcp:compileJava` BUILD SUCCESSFUL

## 비범위 (Out of Scope)
- Slack App 전환 및 Block Kit 버튼 구현 (Incoming Webhook 유지)
- 실제 인프라 조치 도구 구현 (kubectl, kafka-admin 등) — 승인 인프라만 구축
- JDBC 직접 접속 DB 진단 — Prometheus 기반으로 대체
- GitHub 비공개 레포 토큰 인증 — 공개 레포 rate limit 60 req/h 허용
- 새 Gradle 의존성 추가 없음

## 단계별 작업 계획

### 단계 1: 중복 알람 억제 + 분석 실패 시 Slack 알림
- 변경 파일:
  - `mcp/src/main/java/mcp/mcp/agent/AlertDeduplicationService.java` (신규)
  - `mcp/src/main/java/mcp/mcp/agent/AiOpsAgentService.java` (수정)
- 변경 내용:
  - `AlertDeduplicationService`: ConcurrentHashMap<String, Instant>으로 fingerprint→마지막 수신 시각 관리. TTL 30분. 요청 시점에 만료 항목 제거. fingerprint = alertname + severity + 정렬된 labels 연결 문자열
  - `AiOpsAgentService.analyze()`: 시작 시점에 deduplication 체크 → 중복이면 log.info 후 즉시 반환. catch 블록에서 `slackService.send("[AIOps 분석 실패] " + alertName + " 알람 처리 중 오류: " + message)` 호출
- 검증 방법: `.\gradlew.bat :mcp:compileJava`
- 롤백 방법: `git checkout mcp/src/main/java/mcp/mcp/agent/`
- 예상 소요: 보통

### 단계 2: DB 진단 도구 + GitHub 배포 이력 도구
- 변경 파일:
  - `mcp/src/main/java/mcp/mcp/tools/ObservabilityTools.java` (수정)
  - `mcp/src/main/java/mcp/mcp/config/RestClientConfig.java` (수정)
  - `mcp/src/main/resources/application.yaml` (수정)
- 변경 내용:
  - `ObservabilityTools`: 기존 queryPrometheusMetrics 내부 HTTP 로직을 `callPrometheus(String promql)` private 메서드로 추출. `queryDatabaseHealth()` 도구 추가 — MySQL(threads_connected, slow_queries rate, hikaricp_connections_pending)·Redis(memory_used_bytes, connected_clients) 5개 PromQL을 배치 조회 후 섹션별 요약 반환. `queryRecentCommits(int minutes)` 도구 추가 — GitHub REST API `/repos/{owner}/{repo}/commits?since=...` 호출 후 extractCommitSummaries()로 "ISO시간 | 작성자 | 커밋 첫 줄" 줄글만 추출 (GitHub 원본 JSON 미전달)
  - `RestClientConfig`: `githubClient` 빈 추가. baseUrl = https://api.github.com, Accept = application/vnd.github.v3+json, timeout 5s
  - `application.yaml`: `github.owner`, `github.repo` 키 추가
- 검증 방법: `.\gradlew.bat :mcp:compileJava`
- 롤백 방법: `git checkout mcp/src/main/java/mcp/mcp/tools/ mcp/src/main/java/mcp/mcp/config/ mcp/src/main/resources/application.yaml`
- 예상 소요: 김

### 단계 3: 연쇄 장애 추론 + 배포 이력 조회 프롬프트 개선
- 변경 파일:
  - `mcp/src/main/java/mcp/mcp/agent/AiOpsAgentService.java` (SYSTEM_PROMPT 수정)
- 변경 내용:
  - 기존 5단계 절차에 다음 추가:
    - 2번 단계 직후: `queryDatabaseHealth()`를 호출해 DB·캐시 이상 여부 확인
    - 4번 단계(Loki 로그 조회) 직후: `queryRecentCommits(60)`를 호출해 최근 1시간 배포 이력과 장애 발생 시각 상관관계 분석
    - 연쇄 장애 추론 지시 추가: "Kafka 컨슈머 랙 증가 → CDC 추출 지연 → API 스레드 포화 같은 다단계 인과관계를 명시적으로 탐색하라. 원인이 여러 계층에 걸쳐 있다면 각 계층을 순서대로 서술하라."
    - 배포 상관관계 지시 추가: "최근 커밋이 있고 장애 발생 시각과 10분 이내로 겹친다면, 해당 커밋을 원인 후보로 명시하고 롤백 여부를 권장 조치에 포함하라."
- 검증 방법: 파일 내용 직접 확인
- 롤백 방법: `git checkout mcp/src/main/java/mcp/mcp/agent/AiOpsAgentService.java`
- 예상 소요: 짧음

### 단계 4: 인간 승인 게이트 인프라
- 변경 파일:
  - `mcp/src/main/java/mcp/mcp/approval/ActionApprovalService.java` (신규)
  - `mcp/src/main/java/mcp/mcp/approval/ActionApprovalController.java` (신규)
  - `mcp/src/main/java/mcp/mcp/tools/ObservabilityTools.java` (수정 — proposeAction 도구 추가)
- 변경 내용:
  - `ActionApprovalService`: ConcurrentHashMap<String, PendingAction>. `propose(actionType, params, reason)` → UUID 생성 후 저장 후 반환. `approve(id)` → lookup 후 로그 출력(향후 executor 연결 예정). TTL 1시간으로 만료 항목 자동 제거
  - `ActionApprovalController`: `POST /action/approve/{id}` — approve() 호출 후 200 OK 반환
  - `ObservabilityTools`: `proposeAction(actionType, params, reason)` 도구 추가 → ActionApprovalService.propose() 호출 후 "승인 명령어: curl -X POST http://aiops:8085/action/approve/{id}" 형식 반환
- 검증 방법: `.\gradlew.bat :mcp:compileJava`
- 롤백 방법: `git checkout mcp/src/main/java/mcp/mcp/tools/ObservabilityTools.java && rm mcp/src/main/java/mcp/mcp/approval/`
- 예상 소요: 보통

## 리스크 및 대응
- 리스크: GitHub API rate limit 60 req/h 초과 → 대응: queryRecentCommits 실패 시 "조회 실패 — 스킵" 반환하고 분석 계속 진행 (다른 도구와 동일 패턴)
- 리스크: deduplication ConcurrentHashMap이 장기 운영 시 메모리 누수 → 대응: 요청 시점 만료 항목 제거로 최대 크기 자연 억제. 고트래픽 시 Caffeine 캐시로 교체 권장 (이번 범위 밖)
- 리스크: proposeAction 도구가 무분별하게 호출되어 승인 store 오염 → 대응: 시스템 프롬프트에 "데이터로 뒷받침된 원인이 특정된 경우에만 호출" 조건 명시

## 의존성
- Spring Boot 3.x, Lombok, Jackson ObjectMapper — 모두 기존 의존
- GitHub REST API — 공개 레포, 별도 인증 없음
- 새 Gradle 의존성 없음
