# /pr-review 적용 값 — promotion 프로젝트

범용 흐름은 같은 폴더의 `SKILL.md`. 이 파일은 이 프로젝트에만 해당하는 값이고, 리뷰어(`code-reviewer`)도 이 파일을 읽는다.

## 위험 변경 (하나라도 해당하면 리뷰 필수)
| 영역 | 판단 기준 (경로·내용) |
|------|------|
| 결제·금액 | `serverC/**`, serverA 주문·Saga(`OrderCommandService`, `SagaOrchestratorService`, `scheduler/`), 금액·환불 로직 |
| 동시성 | 조건부 UPDATE·락·선점, 스케줄러, Redis 재고·Saga 상태 |
| 보안·인가 | `gateway-service/**` 필터·라우트, 관리자 API, `X-User-*` 헤더 처리, 인증 |
| DB 마이그레이션 | `*/src/main/resources/db/migration/**` |
| 이벤트 | `*/kafka/**`, Outbox 저장, 메시지 DTO(`dto/*Message.java`), `KafkaTopicConfig`, `debezium/**` |

해당 없음(문서만, 단순 화면·CRUD·설정값)이면 리뷰는 선택이다.

## 제외 파일 (리뷰 대상에서 뺀다)
- `*.md` (문서 — 명세는 "맥락 문서"로 따로 읽는다)
- `frontend/src/api/generated/**` (openapi-typescript 생성물), `package-lock.json`
- `build/`, `playwright-report/`, `test-results/`

## 맥락 문서 (필요한 부분만 발췌해서 읽는다)
- **명세**: `docs/backlog.md` "진행 중"의 `문서` 칸 또는 PR 브랜치 커밋에 포함된 `docs/specs/*.md`. 정책(P-n)·시나리오(S-n)·"장애 시 동작" 표·결정 기록
- **설계 불변식**: `docs/design-notes.md` — 바뀐 도메인 키워드로 검색해 해당 항목만
- **구조**: `docs/architecture/arch-snapshot.md` — 토픽명·클래스명으로 검색해 해당 행만 (436행 "리뷰어 주의": Outbox 발행 토픽에 `kafkaTemplate.send()` 직접 호출 금지)
- **규칙**: `CLAUDE.md` "Spring Boot 공통 규칙"·"DB 마이그레이션 작성 원칙"·"절대 금지 사항", 해당 영역 skill(`.claude/skills/{jpa,kafka,api,exception,testing}/SKILL.md`)의 자가 점검 항목

## 전용 점검

### 1. 명세 대조 (명세가 있을 때)
- 이 PR 범위에 해당하는 정책·시나리오·장애 시 동작 행마다 구현과 테스트가 있는가. 빠진 행은 [필수](명세 위반) 또는 [질문](범위 밖인지 불명확)
- 명세에 없는 동작·상태·필드를 추가했는가 → [질문]
- 결정 기록과 다르게 구현했는가 → [필수]

### 2. Kafka·Saga
- 멱등: 같은 메시지가 두 번 와도 결과가 같은가 (멱등 키, 조건부 상태 변경)
- Outbox: 상태 변경과 Outbox 저장이 같은 트랜잭션인가. Outbox 대상 토픽을 `kafkaTemplate`로 직접 보내지 않는가
- 실패: 예외가 재시도→DLT로 가는가, DLT에서 무엇을 남기는가. 재시도해도 안전한가
- 호환성: 메시지는 필드 추가만인가, 소비자 먼저 배포 가능한가 (확장-축소)
- 결과를 모르는 경우(UNKNOWN)·늦게 도착한 결과를 다루는가

### 3. 결제·금액
- 이중 결제·이중 환불이 생길 경로가 있는가 (재시도, 동시 요청, 응답 유실)
- PG 등 외부 호출이 DB 트랜잭션 밖에 있는가. 외부 호출 성공 후 DB 실패 시 어떻게 되는가
- 상태 변경이 현재 상태를 조건으로 하는가 (`WHERE status = ?`)
- 금액은 서버 스냅샷 값을 쓰는가, 승인 금액을 검증하는가

### 4. 프로젝트 규칙
- Controller → Service → Repository, Controller에 로직·`@Transactional` 없음, Entity 직접 반환 없음, Entity에 `@Setter`/`@Data` 없음
- 마이그레이션은 DROP+ADD가 아니라 ALTER/MODIFY/RENAME
- API 모양이 바뀌면 계약 파일(`contracts/openapi.yaml`·`contracts/server-a-openapi.yaml`)도 같은 PR에서 바뀌었는가 (`OpenApiContractTest`)
- 새 클래스·메서드에 테스트가 함께 있는가. 테스트가 실제로 결과를 검증하는가
- 민감 정보(비밀번호·토큰·키)가 로그·응답에 나가지 않는가
- kubectl/helm 명령은 `List<String>`으로 조합하는가
- 새 Gradle 의존성이 있으면 사용자 승인 흔적(PR·plan)이 있는가

## 게시 정보
- 저장소: `gh repo view --json nameWithOwner -q .nameWithOwner`
- PR 본문 템플릿의 "테스트 방법"에는 리뷰 결과를 따로 쓰지 않는다 — review 자체가 PR에 남는다.
