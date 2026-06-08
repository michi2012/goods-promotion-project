# 맥락 노트: Linear MCP 기반 개발 워크플로우 자동화 (명령어 5종 통합)

## 왜 이 방식을 선택했는가
- 포트폴리오용 "AI + MCP 기반 개발 자동화 파이프라인"의 핵심 구간을, 이미 갖춰진 Claude Code 슬래시 커맨드(`/plan`, `/incident`, `/release-notes`)에 Linear MCP를 엮고 신규 명령어 2개(`/spec-draft`, `/spec-to-tickets`)를 더해 완성한다.
- 자동화 파이프라인을 "사람이 트리거하는 대화형 도구(Claude Code)"와 "백엔드가 자동으로 도는 부분(aiops)"으로 명확히 분리했다 — aiops의 자동 RCA 초안·Slack 승인·자동 티켓 생성(Phase 1·2)은 별도 백엔드 코드베이스이자 작업 성격이 달라 이번 plan에서 제외하고 후속 작업으로 분리한다.
- 명세서 입력 형식은 User Story(As a/I want/So that) + Given-When-Then 수용 기준 조합을 채택했다 — 현업에서 널리 쓰이는 형식이면서, 구조가 명확해 LLM이 "한 작업 단위"를 안정적으로 파싱하기 좋다.
- 솔로 워크스페이스이므로 모든 생성 이슈의 담당자는 항상 본인(`me`)으로 고정한다. 다인 팀 라우팅 로직은 의도적으로 만들지 않는다(Simplicity First).

## 검토했으나 채택하지 않은 대안
### 대안 A: 신규 명령어 없이 기존 4개(`/plan`,`/incident`,`/release-notes`,`/db-migration`)에만 Linear 연동
- 무엇: 명세서→티켓 변환은 사용자가 수동으로 하고, 기존 명령어에만 연동을 추가
- 왜 안 썼나: 사용자가 처음 구상한 파이프라인의 핵심("명세서 작성 → 자동 티켓 생성")이 빠져 포트폴리오 스토리의 설득력이 떨어짐. `/db-migration`은 1회성 검토 도구라 티켓 단위 추적 가치가 낮아 애초에 연동 대상에서 제외했다.

### 대안 B: aiops 자동화(Phase 1·2)까지 이번 plan에 포함
- 무엇: aiops 서버의 알람 감지 → RCA 초안 생성 → Slack 승인 게이트 → Linear 티켓 자동 생성까지 한 번에 구현
- 왜 안 썼나: 작업 성격(백엔드 서버 코드, 별도 코드베이스)과 검증 방식(Claude Code 명령어와 무관)이 완전히 달라 같은 plan에 묶으면 단계별 검증 단위가 모호해진다. 별도 plan으로 분리하는 게 Goal-Driven Execution 원칙에 부합한다.

### 대안 C: `/spec-to-tickets` 1개만 먼저 만들어 검증 후 점진 확장
- 무엇: 가장 핵심적인 명령어 하나로 엔드투엔드를 먼저 증명하고, 다음 plan에서 나머지를 확장
- 왜 안 썼나: 사용자가 A안(전체 5개를 한 plan에서 진행)을 명시적으로 선택했고, Linear MCP 연결까지 사전 검증을 마쳐 외부 의존 리스크가 해소된 상태라 판단했다.

## 기존 코드베이스 컨벤션
- 슬래시 커맨드 위치: `.claude/commands/*.md`
- 명령어 구조 패턴: "사용법 안내 → Step별 순차 진행 → 출력 포맷 예시(코드 블록) → 파일 생성/실행 전 사용자 승인(y/n)" — `incident.md`, `db-migration.md`, `release-notes.md` 모두 동일한 골격
- 데이터 수집·가공은 PowerShell 블록이 먼저 처리하고, Claude는 그 출력을 해석해 다음 단계를 진행하는 분업 구조 (`release-notes.md` Step 1: "Claude가 커밋을 분류하지 않는다. PowerShell이 타입별로 그룹핑까지 처리한다")
- 위험하거나 되돌리기 어려운 작업(파일 생성, git commit/tag, 외부 시스템 변경) 직전에는 항상 사용자 승인을 받는 패턴이 일관되게 적용됨

## 관련 파일/위치
- `.claude/commands/plan.md` — Step 1·5에 Linear 이슈 조회/상태 전환 추가 대상
- `.claude/commands/incident.md` — Step 1에 aiops 초안 인식 문구, Step 4 뒤에 Linear 서브태스크 생성(신규 Step 5) 추가 대상
- `.claude/commands/release-notes.md` — Step 1에 이슈 ID 추출, Step 3에 "포함된 이슈" 섹션, Step 4 뒤에 상태 전환 추가 대상
- `.claude/commands/spec-draft.md` — 신규 작성
- `.claude/commands/spec-to-tickets.md` — 신규 작성
- `.mcp.json` — Linear MCP 서버 등록 완료 (HTTP 트랜스포트 + OAuth, 자격증명 미저장)

## 외부 참조
- Linear MCP 공식 문서: https://linear.app/docs/mcp
- 연동 검증된 워크스페이스: 팀 `Michi2012` (key: `MIC`), 계정 `gdamhyeon@gmail.com`
