# 맥락 노트: AIOps 에이전트 고도화 (5개 영역)

## 왜 이 방식을 선택했는가

### DB 진단 도구 — Prometheus 기반 선택 이유
Alertmanager 웹훅을 수신하는 에이전트가 MySQL에 직접 JDBC 커넥션을 여는 것은 관심사 분리 위반이고, spring-jdbc 의존성 추가가 필요하며, DB 접근 권한 관리 surface가 늘어난다. 반면 Prometheus에는 mysql_exporter·redis_exporter가 이미 지표를 수집 중이다. 슬로우 쿼리 카운트, 커넥션 수, HikariCP 대기 커넥션, Redis 메모리 사용률만으로도 SRE 초동 진단에 필요한 90%가 커버된다. 실시간 SHOW PROCESSLIST가 필요한 심층 진단은 DBA 직접 접속으로 위임한다.

### GitHub 전처리 — extractCommitSummaries 패턴 선택 이유
GitHub REST API `/commits` 응답 JSON 1건당 약 4~8KB이며, 아바타 URL, 노드 ID, 패치 URL 등 AI 분석에 무의미한 필드가 80%를 차지한다. 20건이면 최대 160KB — 현재 모델(gemini-3.1-flash-lite)의 컨텍스트 창을 불필요하게 잠식하고 분석 속도를 저하시킨다. 백엔드에서 "ISO시간 | 작성자 | 첫 줄 메시지"만 추출해 평균 1건당 100바이트로 압축하면, AI는 배포 이력을 정확하게 이해하면서 토큰을 아낀다.

### 중복 억제 TTL 30분 선택 이유
Alertmanager의 기본 설정: group_wait 30s, group_interval 5m, repeat_interval 1h. 실제 같은 알람이 resolve 없이 30분 안에 다시 오는 경우는 Alertmanager의 repeat 발송이다. 이를 모두 분석하면 Claude API 비용이 기하급수적으로 증가한다. 30분이면 "같은 장애가 지속 중인 재발송"은 억제하면서, "일단 resolve됐다가 새로 발생한 동일 알람"은 통과시킬 수 있는 균형점이다.

### 인간 승인 게이트 — 백엔드 인프라만 선택 이유
Slack 버튼(Block Kit Interactive Message)을 사용하려면 Incoming Webhook에서 Slack App(OAuth 2.0, Event Subscription URL, Bot Token Scope 설정)으로 전환해야 한다. 현재 에이전트는 분석·보고만 하고 인프라 액션을 취하지 않으므로, 게이트할 대상 자체가 없다. 지금은 "향후 kubectl scale, kafka-admin 같은 액션 도구가 추가될 때 연결할 수 있는 인프라"를 구축하는 것이 현실적이다. 승인 엔드포인트(`POST /action/approve/{id}`)를 통해 엔지니어가 curl로 수동 승인할 수 있어 테스트 가능성도 확보된다.

## 검토했으나 채택하지 않은 대안

### 대안 A: JDBC 직접 접속 DB 진단
- 무엇: spring-jdbc 추가 후 `SHOW FULL PROCESSLIST`, `INFORMATION_SCHEMA.INNODB_TRX` 직접 조회
- 왜 안 썼나: 새 Gradle 의존성 추가(사전 승인 필요), DB 자격증명을 에이전트 서비스에 노출, 관심사 분리 위반. Prometheus exporter로 충분히 대체 가능.

### 대안 B: Slack Interactive Message (Block Kit 버튼)
- 무엇: AI가 "Kafka 리밸런싱 실행할까요?" 메시지와 함께 버튼 전송 → 엔지니어 클릭 → 즉시 실행
- 왜 안 썼나: Incoming Webhook은 단방향 발송만 가능 — Slack App(OAuth) 전환 필요. 현재 에이전트에 실행할 액션 도구가 없으므로 껍데기 구현이 됨. 향후 액션 도구 추가 시 함께 구현 예정.

### 대안 C: Redis/Caffeine 캐시 기반 중복 억제
- 무엇: ConcurrentHashMap 대신 Caffeine(expireAfterWrite 30분) 사용
- 왜 안 썼나: 새 Gradle 의존성 추가 필요. 단일 인스턴스 환경에서 ConcurrentHashMap으로 완전히 동일한 기능 구현 가능. 수평 확장이 필요해지면 Redis 기반으로 교체.

### 대안 D: GitHub webhook 수신 기반 배포 이력
- 무엇: GitHub push 이벤트를 별도 웹훅으로 수신해 메모리에 최근 배포 이력 저장
- 왜 안 썼나: GitHub webhook 설정(비밀 토큰, 수신 엔드포인트 공개 URL) 필요. 서버 재시작 시 이력 소실. queryRecentCommits로 API Pull 방식이 더 단순하고 stateless.

## 관련 파일/위치
- `mcp/src/main/java/mcp/mcp/agent/AiOpsAgentService.java` — 분석 오케스트레이터, SYSTEM_PROMPT 보유
- `mcp/src/main/java/mcp/mcp/agent/AlertDeduplicationService.java` (신규) — fingerprint 기반 중복 억제
- `mcp/src/main/java/mcp/mcp/tools/ObservabilityTools.java` — AI 도구 모음 (Loki, Tempo, Prometheus, GitHub, DB, proposeAction)
- `mcp/src/main/java/mcp/mcp/config/RestClientConfig.java` — HTTP 클라이언트 빈 등록
- `mcp/src/main/java/mcp/mcp/approval/ActionApprovalService.java` (신규) — 승인 대기 store
- `mcp/src/main/java/mcp/mcp/approval/ActionApprovalController.java` (신규) — POST /action/approve/{id}
- `mcp/src/main/resources/application.yaml` — github.owner, github.repo 설정 추가
