# 계획서: codex 리뷰 도구 제거 + PR 리뷰 skill 신설 (BL-032)

- 작성일: 2026-11-14
- 관련 이슈/티켓: BL-032 (`docs/backlog.md`)

## [완료] BL-032 (PR #49)

### 목표
연결이 불안정한 codex 리뷰 도구를 없애고, PR을 올린 뒤 "팀원"(새 컨텍스트의 리뷰어 에이전트)이 PR을 리뷰해 [필수]/[제안]/[질문]/[사소] 코멘트를 PR에 남기는 `/pr-review` skill을 만든다.

### 성공 기준
- [ ] codex 흔적이 저장소에서 사라진다 — `grep -ri codex`가 `docs/CHANGELOG.md`(이력)·`docs/batch/`(미추적) 외에 없음. `.mcp.json`·`settings*.json`은 JSON으로 유효
- [ ] `.claude/skills/pr-review/`(SKILL.md + project.md)와 개편한 `code-reviewer` 에이전트가 정한 흐름을 담는다: PR diff 수집 → 리뷰어(작성자 설명 없이) → [필수] 재검증 → 미리보기 → 승인 후 게시 → 반영·재리뷰
- [ ] `/pr`, `/plan` checklist 템플릿, CLAUDE.md가 새 리뷰 단계를 가리킨다 (단계별 "코드리뷰 통과" 제거, 최종 검증에 "PR 리뷰 1회 — 위험 변경 필수")
- [ ] PR #48에 `/pr-review`를 실제로 적용해 미리보기가 정한 형식으로 나오고, 승인 후 PR에 게시된다. 토큰 사용량을 기록한다

### 비범위
- PR #48 리뷰에서 나온 [필수]·[제안]의 코드 반영 (PR #48은 머지됨 → 필요하면 별도 브랜치·백로그)
- CI 자동 리뷰 (사용자 결정: 넣지 않음)
- OpenAI 키 폐기 (사용자가 OpenAI 콘솔에서 직접)
- `docs/CHANGELOG.md`의 과거 codex 기록

### 단계별 작업 계획

#### 단계 1: codex 흔적 제거
- 변경 파일: `.claude/commands/codex-review.md`(삭제), `.claude/settings.json`, `.claude/commands/arch-snapshot.md`, `README.md`, `docs/architecture/arch-snapshot.md` / 로컬 전용(미추적): `.mcp.json`, `.claude/settings.local.json`
- 변경 내용: 명령 파일 삭제, MCP 서버 항목·활성화 목록에서 codex 제거, 다른 문서의 codex 언급을 `/pr-review`·"리뷰어"로 교체
- 검증: `grep -ri codex` (위 예외 외 0건), JSON 파싱 확인
- 롤백: `git checkout` + `git restore` (미추적 파일은 수정 전 내용을 스크래치패드에 백업)
- 예상 소요: 짧음

#### 단계 2: `/pr-review` skill 작성
- 변경 파일: `.claude/skills/pr-review/SKILL.md`(범용 흐름·코멘트 형식·처리 규칙), `.claude/skills/pr-review/project.md`(이 프로젝트 점검 기준·제외 파일·명세 위치)
- 변경 내용: 다른 skill(e2e·spec)처럼 범용/프로젝트 값을 나눈다. 리뷰어에게는 PR 번호·변경 파일·관련 명세 경로만 넘기고 작성자 설명은 넘기지 않는다. 게시는 `gh api` PR review 1개(event COMMENT, 줄 단위 + 요약). [사소]는 요약에만.
- 검증: 문서 육안 확인 (실동작은 단계 5)
- 롤백: 새 폴더 삭제
- 예상 소요: 보통

#### 단계 3: `code-reviewer` 에이전트 개편
- 변경 파일: `.claude/agents/code-reviewer.md`
- 변경 내용: 출력을 [필수]/[제안]/[질문]/[사소] + 파일:줄 + 근거(명세 ID 등) 구조로. [필수]는 보고 전 코드를 다시 읽어 확인. 프로젝트 점검(명세 대조, Kafka·Saga, 결제·금액, 프로젝트 규칙)은 skill `project.md`를 읽게 하고, Spring/JPA 세부는 기존 skill(jpa·kafka 등) 참조로 줄인다. diff 중심·큰 문서 발췌로 토큰을 아낀다.
- 검증: 문서 육안 확인 (실동작은 단계 5)
- 롤백: `git checkout`
- 예상 소요: 보통

#### 단계 4: 기존 규칙에 연결
- 변경 파일: `.claude/commands/pr.md`, `.claude/commands/plan.md`, `CLAUDE.md`
- 변경 내용: `/pr` Step 3 뒤에 "위험 변경이면 `/pr-review`" 안내. `/plan` checklist 템플릿의 단계별 "코드리뷰 통과"를 빼고 최종 검증에 "PR 리뷰 1회(위험 변경 필수)". CLAUDE.md "작업 시작 시" skill 목록과 "커밋 전 검증" 근처에 PR 리뷰 한 줄.
- 검증: 문서 육안 확인 + 서로 가리키는 경로가 실제 존재하는지 확인
- 롤백: `git checkout`
- 예상 소요: 짧음

#### 단계 5: PR #48에 첫 적용
- 변경 파일: 없음 (PR #48에 코멘트 게시 — 외부 게시라 승인 후)
- 변경 내용: `/pr-review 48` 실행 → 미리보기 보고 → 승인 시 게시. 리뷰 1회 토큰 사용량을 기록하고, 너무 크면 skill의 절약 장치를 조정한다.
- 검증: 미리보기가 정한 형식([필수] 재검증 표시 포함)이고, 게시 후 PR #48에 review 1개(줄 단위 + 요약)가 보인다
- 롤백: 게시한 review 코멘트 삭제 (`gh api` DELETE)
- 예상 소요: 보통

### 리스크 및 대응
- 같은 계정이라 Approve/Request changes 불가 → event `COMMENT`로만 게시, 판정은 요약 본문에
- 줄 단위 코멘트의 줄 번호가 diff 밖이면 GitHub API가 거절 → diff 안의 줄만 줄 단위로, 나머지는 요약에
- 리뷰어 오탐 → [필수]는 리뷰어가 재검증한 것만, 게시 전 미리보기에서 사용자가 걸러냄
- PR #48이 이미 머지된 상태 → 코멘트는 가능, [필수]는 후속 브랜치·백로그로

### 의존성
- `gh` CLI 인증(현재 PR 생성에 사용 중)
- 새 의존성 없음
