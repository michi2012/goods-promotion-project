# codebot 전체 흐름

codebot은 Slack을 통해 위임받은 코드/운영 조사 요청을 처리하는 개발자 지원 챗봇(Worker)이다. 자체 Slack 인터페이스는 없고, **aiops의 RouterService가 CODE로 분류한 메시지만 내부 HTTP로 위임**받아 처리한다.

## 1. 진입점

```
POST /internal/investigations
Body: { "conversationId": "...", "message": "..." }  (둘 다 @NotBlank, InvestigationController에 @Validated)
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

**대화 메모리**: aiops의 InfraChatAgentService와 동일한 패턴 — `ChatMemoryConfig`의 `MessageWindowChatMemory`(최대 20메시지, 인메모리 `InMemoryChatMemoryRepository`)를 `MessageChatMemoryAdvisor`로 연결하고, `conversationId`(aiops가 전달하는 Slack `threadTs`)를 `ChatMemory.CONVERSATION_ID`로 사용한다 — 같은 스레드에서 이어지는 질문의 맥락을 기억한다(서버 재시작 시 초기화).

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
| `searchCode(query)` | `git grep -n -i -e {query}`로 키워드 검색 + 파일 경로 자체에 쿼리가 포함되는 경우도 `git ls-files`로 추가 매칭해 함께 반환 |
| `getFileContent(path)` | 파일 전체 내용 조회(최대 1만자 절삭) |
| `previewDiff(filePath, newContent)` | `java-diff-utils`(`DiffUtils`/`UnifiedDiffUtils`)로 unified diff 생성, ` ```diff ` 코드블록으로 반환 |

git-sync 사이드카가 1분 주기로 메인 브랜치를 로컬(`codebot.repo.local-path`, 기본 `/repo/current`)에 동기화해두고, codebot은 그 로컬 클론을 grep으로 검색한다. 별도 인덱싱/임베딩이 필요 없어 코드 검색에는 Vector Store보다 이 방식이 적합하다.
- **경로 탈출 방지**: `getFileContent`/`previewDiff`는 `repoPath.resolve(path).normalize()` 후 `resolved.startsWith(repoPath)`를 검사 — `../../etc/passwd` 같은 경로 탈출 요청을 차단한다.
- **경로 자동 보정**: LLM이 준 경로가 정확히 일치하지 않으면 `git ls-files` 결과에서 **파일명(basename)** 이 대소문자 무시하고 유일하게 일치하는 파일을 자동으로 찾아 사용한다(일치가 여러 개면 후보 목록을 반환하고 사용자에게 정확한 경로를 요구).

### ObservabilityTools / KubernetesTools — ⚠️ aiops와 "동일한 도구 세트"가 아니라 **부분집합** + **읽기 전용**
codebot의 `ObservabilityTools`/`KubernetesTools`는 aiops의 동명 클래스와 **패키지가 다른 완전히 별도의 구현체**다(공유 라이브러리 아님, 코드 중복). 실제로는 aiops보다 도구 수가 적고, K8s 쪽은 조회 기능 하나뿐이다.

| 클래스 | codebot 보유 도구 | aiops에만 있고 codebot에는 없는 도구 |
|---|---|---|
| ObservabilityTools | `queryLokiLogs`, `queryTempoTrace`, `queryProfilerHotspots`, `queryPrometheusMetrics` (4개) | `queryDatabaseHealth`, `queryRecentCommits`, `queryKafkaLag`, `queryDebeziumConnectorStatus`, `proposeDebeziumConnectorRestart`, `proposeAction` |
| KubernetesTools | `getClusterStatus()` (Pod+Deployment만, 1개) | `getPodLogs`, `getRolloutStatus`, `getRolloutHistory`, `getIstioMeshStatus`, HPA 조회 및 `propose*`(롤아웃재시작/Pod재시작/HPA패치/Helm롤백/트래픽시프트/OutlierDetection) 전부 |

즉 **codebot은 K8s/인프라에 어떤 변경도 제안·실행할 수 없다** — 승인 대기열(`ActionApprovalService`)이나 Slack 버튼 흐름 자체가 codebot에는 없다. 코드 조사 중 참고용으로 로그/트레이스/메트릭/프로파일/Pod 상태를 "조회"만 하도록 범위가 의도적으로 좁혀져 있다.

### DataQueryTools — 데이터 조회
`executeQuery(database, sql)` 하나로 order/payment/user DB에 화이트리스트 기반 읽기 전용 SQL을 실행한다.
- SELECT 단일 문장만 허용(정규식 `^\s*SELECT\s`), 세미콜론으로 여러 문장 연결 금지
- `SELECT *` 금지 — 화이트리스트 테이블/컬럼 표:

  | DB | 테이블 | 컬럼 |
  |---|---|---|
  | order | `orders` | id, order_id, user_id, goods_id, quantity, payment_method, status, created_at, updated_at |
  | order | `goods` | id, name, stock |
  | payment | `payments` | id, order_id, user_id, goods_id, quantity, payment_method, status, created_at |
  | user | `users` | id, user_id, username, role, created_at, updated_at |

  (email/password/phone_number/shipping_address 등 개인정보 컬럼은 화이트리스트에 없어 조회 시 DB 권한 오류로 실패)
- `FROM`/`JOIN` 절의 테이블명을 정규식으로 추출해 화이트리스트 외 테이블이면 차단
- LIMIT 미지정 시 자동 100, 결과 1만자 초과 시 절삭
- **DB 연결 지연 초기화**: `DataQueryDataSourceConfig`가 `HikariDataSource.setInitializationFailTimeout(-1)`을 설정 — 자격증명이 비어있거나 DB가 아직 기동 전이어도 애플리케이션 컨텍스트는 정상 시작되고, 실제 연결은 최초 쿼리 실행 시점에 시도된다(codebot 기동이 order/payment/user DB 가용성에 의존하지 않도록 하는 설계).

### LinearTools — 이슈 생성
`createIssue(title, description, domainLabel, roleLabel)`. SYSTEM_PROMPT 규칙: 조사당 1회만 호출, 생성 여부를 사용자에게 묻지 않고 즉시 호출(되돌리기 쉬운 작업이라 판단). 운영 이슈(RCA, "가설:" 표기)와 코드 품질 개선 요청(실제 코드 인용)을 구분해 description을 작성.
- `domainLabel`은 "주문/결제/프로모션/유저" 중 하나, `roleLabel`은 "백엔드/프론트엔드/인프라" 중 하나 — Linear GraphQL로 라벨을 조회해 **부모 그룹명이 각각 "도메인"/"직무"인지 검증**한 뒤 라벨 ID를 사용(엉뚱한 그룹의 라벨을 잘못 붙이는 것을 방지).
- `resolveViewerId()`로 API 키 소유자(viewer) 자신을 **자동 assignee로 지정**한다.
- description에 `PR_CHECKLIST`(테스트 작성/API문서 갱신/민감정보 노출 확인 3항목)를 자동으로 덧붙인다 — LLM은 이 섹션을 직접 작성하지 않도록 프롬프트에 명시되어 있다.

### PullRequestTools — 코드 수정 PR
`createFixPullRequest(filePath, newContent, issueIdentifier, commitMessage, prTitle, prBody)`.
- 사용자가 "고쳐서 PR 올려줘"처럼 명시적으로 요청했을 때만 호출(RCA만으로 자동 호출 금지)
- 단일 파일 수정으로 명확히 식별될 때만 — 여러 파일 필요 시 호출하지 않고 안내만
- **반드시 `previewDiff`로 사용자 동의를 먼저 받은 후에만 호출** (브랜치+커밋+PR 생성은 실제 변경을 일으키므로)
- **보호 경로 차단**: `.github/`로 시작하는 경로, `.env`/`.env.*`는 파일 경로 지정 단계와 자동 보정 이후 경로 모두에서 이중으로 차단된다(CI 설정·환경변수 파일 보호).
- **경로 자동 보정**: `CodeSearchTools`와 동일하게 정확한 경로가 없으면 basename으로 유일 매칭을 찾는다.
- **브랜치명 규칙**: `feature/{issueIdentifier 소문자}-codebot-fix` — `issueIdentifier`로 Linear `Closes` 연동뿐 아니라 브랜치명까지 결정한다.
- **멱등 동작**: 브랜치가 이미 있으면 새로 만들지 않고 같은 브랜치에 커밋만 추가(`getFileSha`로 최신 sha 조회 후 GitHub Contents API로 커밋). 이미 열린 PR이 있으면 새 PR을 만들지 않고 기존 PR URL을 그대로 반환. 브랜치는 있지만 열린 PR이 없으면(머지/클로즈됨) 그 사실을 안내하고 새 PR을 만들지 않는다.
- PR 본문은 사용자가 준 `prBody`에 `"## 관련 이슈\nCloses {issueIdentifier}"` + `PR_CHECKLIST` + 자동 생성 경고 배너(`PR_NOTICE`: "codebot이 자동 생성한 PR입니다. 머지 전 리뷰·테스트가 필요합니다.")를 자동으로 덧붙여 완성한다.

## 5. 보안/권한 경계

- **DataQueryTools**: order/payment/user DB에 대해 codebot 전용 **읽기 전용(RO)** 계정 사용, 화이트리스트 테이블·컬럼만 접근 (`CsBotTools`처럼 호출자가 아니라 PO/기획 등 내부 사용자를 대상으로 함 — cs-bot과 달리 본인 데이터 제한 없음, 대신 DB 자체를 RO+화이트리스트로 제한)
- **PullRequestTools**: 실제 git 변경을 일으키는 유일한 도구라 사용자 동의를 명시적으로 요구. 추가로 `.github/`·`.env`·`.env.*` 경로는 코드 레벨에서 수정 자체를 차단(`isProtectedPath`)
- **CodeSearchTools**: git-sync 볼륨은 `:ro`(read-only)로 마운트 — codebot이 직접 git 작업을 하지 않음. 파일 조회 도구(`getFileContent`/`previewDiff`)는 `repoPath` 하위로 정규화된 경로만 허용해 `../` 경로 탈출을 코드 레벨에서 차단
- **KubernetesTools/ObservabilityTools**: aiops와 달리 `propose*`/조치 실행 도구가 전혀 없다 — codebot은 구조적으로 K8s나 인프라를 변경할 수 없고 조회만 가능(Human-in-the-loop 승인 큐 자체가 codebot에 없음)

## 6. 사용 모델

Chat/Tool Calling만 사용 (`gemini-3.1-flash-lite`), Structured Output·Embedding 없음 — 코드 검색은 grep 기반이라 Vector Store가 불필요하다.

## 7. 외부 의존성

- **Eureka**: aiops가 codebot을 찾기 위해 등록(codebot 자신은 다른 서비스를 찾지 않음)
- **git-sync 사이드카**: GitHub 레포를 1분 주기로 `/repo/current`에 동기화, `codebot.repo.local-path`(기본 `/repo/current`) 프로퍼티로 codebot 컨테이너에 read-only 마운트 경로 지정
- **MySQL(order/payment/user) RO 계정**: `data-query.datasource.{order,payment,user}.{url,username,password}` — 운영 RW 계정과 분리. `HikariDataSource.initializationFailTimeout(-1)`로 DB 미가용 시에도 앱은 기동됨(연결은 쿼리 시점에 지연 시도)
- **Prometheus/Loki/Tempo/Pyroscope**: aiops와 별도 구현체로 직접 URL 접속(4개 도구만 보유 — 위 "ObservabilityTools/KubernetesTools" 표 참고)
- **GitHub API**: PR 생성(`PullRequestTools`). `githubClient`는 `github.token` 프로퍼티가 설정된 경우에만 `Authorization: Bearer` 헤더를 붙인다(미설정 시 비인증 요청 — private 레포에서는 사실상 필수)
- **Linear API**: 이슈 생성(`LinearTools`)

### RestClientConfig — 타임아웃 정책

| Bean | connect/read timeout | 비고 |
|---|---|---|
| `lokiClient`/`tempoClient`/`prometheusClient`/`pyroscopeClient` | 3초/3초 | aiops와 동일 패턴 |
| `linearClient` | 기본값 | baseUrl `https://api.linear.app/graphql` |
| `githubClient` | 5초/5초 | baseUrl `https://api.github.com`, 토큰 조건부 첨부 |

aiops와 달리 codebot의 `RestClientConfig`에는 `kafkaConnectClient`/`slackClient` 계열 빈이 없다 — codebot은 Kafka Connect나 Slack에 직접 접속하지 않고(Slack 응답은 항상 aiops를 경유), 그만큼 aiops보다 외부 연결 지점이 적다.
