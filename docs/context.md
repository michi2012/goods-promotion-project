# 맥락 노트: codex 리뷰 도구 제거 + PR 리뷰 skill 신설 (BL-032)

## [진행 중] BL-032

### 왜 이 방식을 선택했는가
- BL-029 PR① 커밋 전 리뷰에서 codex MCP가 연결되지 않았고(CONNECTION_CLOSED), 리뷰 없이 커밋했다. 사용자가 codex를 없애고 Claude 기반 리뷰를 고도화하기로 결정.
- **팀원 리뷰 방식** (사용자 제안): 현업처럼 PR을 올린 뒤 리뷰 → 코멘트 → 반영 커밋 → 재리뷰. 리뷰 이력이 PR에 남아 포트폴리오에서도 보인다. 테스트·E2E는 지금처럼 커밋 전, 리뷰만 PR 뒤로.
- **코멘트 구분** (Conventional Comments식): [필수]만 머지를 막는다(버그·데이터 손상·보안·명세 위반). [제안]·[질문]은 작성자가 판단해 답글, [사소]는 요약에만.
- **리뷰어 1개 + [필수]만 재검증**: 관점별 리뷰어 여러 개는 같은 파일을 여러 번 읽어 토큰이 몇 배. 재검증은 리뷰어 안에서 한다 — 작성자(구현 세션)가 재검증하면 작성자 판단이 다시 끼어든다.
- **리뷰어에게 작성자 설명을 넘기지 않음**: 새 컨텍스트로 띄우는 이유(작성자 판단에 끌려가지 않음)를 지키기 위해.
- **현 세션 서브에이전트**: 별도 세션과 효과가 비슷하고 반영 흐름이 끊기지 않는다.
- **PR 코멘트로 게시, 미리보기 승인 후**: 공개 저장소라 외부 게시. 오탐·민감 내용은 게시 전에 거른다.
- **PR당 1회, 위험 변경 필수**: 토큰 비용은 횟수가 좌우한다. 위험 변경 = 결제·금액, 동시성, 보안·인가, DB 마이그레이션, 이벤트(토픽·메시지) 변경.
- **이름 `/pr-review`**: 내장 `/code-review`와 겹치지 않게.

### 검토했으나 채택하지 않은 대안
#### 관점별 리뷰어 병렬
- 왜 안 썼나: 토큰 몇 배. 혼자 하는 프로젝트에서 이득 대비 비용이 큼
#### 별도 세션에서 리뷰
- 왜 안 썼나: 매번 세션을 오가야 하고 반영 흐름이 끊김. 서브에이전트도 새 컨텍스트라 독립성은 비슷
#### 로컬 보고서만
- 왜 안 썼나: 세션이 끝나면 사라져 리뷰 이력이 남지 않음
#### 내장 `/code-review --comment`
- 왜 안 썼나: [필수]/[제안] 구분, 명세 대조, 프로젝트 전용 점검, 게시 전 미리보기를 맞추기 어려움. 에이전트가 안 뜰 때의 대체 수단으로만 둔다
#### CI 자동 리뷰
- 왜 안 썼나: API 과금·소음, 로컬 리뷰와 중복, AI 리뷰는 결정적이지 않아 merge 게이트로 부적합

### 기존 코드베이스 컨벤션
- skill 구조: 범용 `SKILL.md` + 프로젝트 값 `project.md` (`.claude/skills/e2e`, `.claude/skills/spec`)
- 에이전트: `.claude/agents/code-reviewer.md` (frontmatter `name`/`description`/`tools`)
- 명령: `.claude/commands/*.md` (`/plan`, `/pr`)

### 관련 파일/위치
- `.claude/agents/code-reviewer.md` — 리뷰어 (개편 대상)
- `.claude/commands/codex-review.md` — 삭제 대상
- `.claude/commands/pr.md`, `.claude/commands/plan.md`, `CLAUDE.md` — 연결 지점
- `.mcp.json`, `.claude/settings.local.json` — 미추적(로컬 전용). PR에 들어가지 않음

### 외부 참조
- Conventional Comments (코멘트 앞 표시 관례)
- GitHub REST: Create a review for a pull request (`POST /repos/{owner}/{repo}/pulls/{n}/reviews`, event COMMENT)
