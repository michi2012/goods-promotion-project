# codebot 전체 흐름

codebot은 Slack을 통해 위임받은 코드/운영 조사 요청을 처리하는 개발자 지원 챗봇(Worker)이다. 자체 Slack 인터페이스는 없고, **aiops의 RouterService가 CODE로 분류한 메시지만 내부 HTTP로 위임**받아 처리한다.

## 1. 진입점

```
POST /internal/investigations
Body: { "conversationId": "...", "message": "..." }
```

Gateway 라우팅 없음 — Slack Bot으로 직접 노출되지 않고, [aiops RouterService](aiops-flow.md#3-흐름-b--slack-대화형-라우팅)가 Eureka로 codebot 인스턴스를 찾아 호출한다.

## 2. 전체 처리 순서

```
1. InvestigationController.investigate(request)
   └─ CodebotAgentService.investigate(conversationId, message)

2. CodebotAgentService
   └─ ChatClient.prompt()
        .system(SYSTEM_PROMPT)   ← 조사 원칙(순서 준수) + 이슈 생성 + PR 생성 + 데이터 조회 지침
        .user(message)
        .advisors(MessageChatMemoryAdvisor)
        .tools(codeSearchTools, observabilityTools, kubernetesTools,
               linearTools, pullRequestTools, dataQueryTools)
        .call().content()

3. 결과를 aiops로 반환 → aiops가 Slack 스레드에 게시
```

## 3. SYSTEM_PROMPT 조사 원칙 (순서 강제)

1. **운영 상태 먼저 확인**: `queryLokiLogs`/`queryTempoTrace`/`queryPrometheusMetrics`/`queryProfilerHotspots`/`getClusterStatus`로 증상의 정확한 위치(에러 클래스, 응답 지연, 핫스팟 메서드)를 먼저 파악한다.
2. **코드 분석은 그 다음**: 1단계에서 얻은 단서(클래스명/메서드명)를 바탕으로 `searchCode` → `getFileContent`로 실제 구현을 확인한다.
3. 확정 안 된 원인은 "가설:"로 표기, 단정하지 않는다.
4. 대화 맥락을 참고해 중복 질문 방지.

**왜 순서를 강제하는가**: 코드를 먼저 보고 추측하면 실제 운영 데이터와 무관한 가설을 세우게 된다. 메트릭/로그/트레이스로 증상 위치를 먼저 좁힌 뒤 코드를 보게 하여 추측성 분석을 줄인다.

## 4. 도구 그룹

### CodeSearchTools — 코드 검색 (git grep 기반, RAG 대체)
| 도구 | 역할 |
|---|---|
| `searchCode(query)` | 키워드로 코드 검색 |
| `getFileContent(path)` | 파일 전체 내용 조회 |
| `previewDiff(filePath, newContent)` | 수정 전/후 diff 미리보기 생성 |

git-sync 사이드카가 1분 주기로 메인 브랜치를 로컬에 동기화해두고, codebot은 그 로컬 클론을 grep으로 검색한다. 별도 인덱싱/임베딩이 필요 없어 코드 검색에는 Vector Store보다 이 방식이 적합하다.

### ObservabilityTools / KubernetesTools
aiops와 동일한 도구 세트(`queryLokiLogs`, `queryTempoTrace`, `queryPrometheusMetrics`, `queryProfilerHotspots`, `getClusterStatus` 등)를 독립적으로 가진다 — codebot은 aiops를 거치지 않고 직접 운영 데이터에 접근한다.

### DataQueryTools — 데이터 조회
`executeQuery(database, sql)` 하나로 order/payment/user DB에 화이트리스트 기반 읽기 전용 SQL을 실행한다.
- SELECT 단일 문장만 허용, `SELECT *` 금지(개인정보 컬럼 차단)
- 화이트리스트 외 테이블 참조 시 차단
- LIMIT 미지정 시 자동 100, 결과 1만자 초과 시 절삭

### LinearTools — 이슈 생성
`createIssue(title, description, domainLabel, roleLabel)`. SYSTEM_PROMPT 규칙: 조사당 1회만 호출, 생성 여부를 사용자에게 묻지 않고 즉시 호출(되돌리기 쉬운 작업이라 판단). 운영 이슈(RCA, "가설:" 표기)와 코드 품질 개선 요청(실제 코드 인용)을 구분해 description을 작성.

### PullRequestTools — 코드 수정 PR
`createFixPullRequest(filePath, newContent, issueIdentifier, commitMessage, prTitle, prBody)`.
- 사용자가 "고쳐서 PR 올려줘"처럼 명시적으로 요청했을 때만 호출(RCA만으로 자동 호출 금지)
- 단일 파일 수정으로 명확히 식별될 때만 — 여러 파일 필요 시 호출하지 않고 안내만
- **반드시 `previewDiff`로 사용자 동의를 먼저 받은 후에만 호출** (브랜치+커밋+PR 생성은 실제 변경을 일으키므로)
- `issueIdentifier`로 Linear `Closes` 연동 — PR 머지 시 이슈 자동 전환

## 5. 보안/권한 경계

- **DataQueryTools**: order/payment/user DB에 대해 codebot 전용 **읽기 전용(RO)** 계정 사용, 화이트리스트 테이블·컬럼만 접근 (`CsBotTools`처럼 호출자가 아니라 PO/기획 등 내부 사용자를 대상으로 함 — cs-bot과 달리 본인 데이터 제한 없음, 대신 DB 자체를 RO+화이트리스트로 제한)
- **PullRequestTools**: 실제 git 변경을 일으키는 유일한 도구라 사용자 동의를 명시적으로 요구
- **CodeSearchTools**: git-sync 볼륨은 `:ro`(read-only)로 마운트 — codebot이 직접 git 작업을 하지 않음

## 6. 사용 모델

Chat/Tool Calling만 사용 (`gemini-3.1-flash-lite`), Structured Output·Embedding 없음 — 코드 검색은 grep 기반이라 Vector Store가 불필요하다.

## 7. 외부 의존성

- **Eureka**: aiops가 codebot을 찾기 위해 등록(codebot 자신은 다른 서비스를 찾지 않음)
- **git-sync 사이드카**: GitHub 레포를 1분 주기로 `/repo/current`에 동기화, `CODEBOT_REPO_PATH` 환경변수로 codebot 컨테이너에 read-only 마운트
- **MySQL(order/payment/user) RO 계정**: `codebot.datasource.{order,payment,user}` — 운영 RW 계정과 분리
- **Prometheus/Loki/Tempo/Pyroscope**: aiops와 동일하게 직접 URL 접속
- **GitHub API**: PR 생성(`PullRequestTools`)
- **Linear API**: 이슈 생성(`LinearTools`)
