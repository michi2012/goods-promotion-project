# 맥락 노트: 브랜치 네이밍 규칙 명시 + GitHub PR 템플릿 동기화

## 왜 이 방식을 선택했는가
- PM 커맨드 파이프라인(`/project-plan` → `/spec-to-tickets` → `/plan`) 구축 후, "실제 현업 팀 프로젝트라면 더 필요한 것"을 논의한 결과 두 가지를 식별:
  1. Linear-GitHub 자동 연동(상태 전환)은 브랜치명에 이슈 식별자가 포함되어야 동작하는데, 이 규칙이 어디에도 명시돼 있지 않았음.
  2. `/spec-to-tickets`가 만드는 "PR 전 체크리스트"는 Linear 이슈 description에만 있어 실제 GitHub PR 작성 화면에는 노출되지 않음.
- 둘 다 "자동화가 실제로 작동하느냐"를 가르는 디테일이라 함께 진행.
- PR 템플릿에 "관련 이슈(Closes MIC-XX)" 섹션을 포함한 이유: 이게 없으면 PR 머지 시 Linear 이슈 자동 완료 전환이 동작하지 않아 자동화의 핵심 가치를 놓치게 됨 (사용자 확인 — Q1=B).
- 브랜치 네이밍은 기존 `feature/...` 프리픽스 컨벤션(현재 브랜치 `feature/frontend-foundation`)을 유지하면서 식별자만 끼워넣는 `feature/mic-{n}-{slug}` 형식을 선택 — 기존 컨벤션을 깰 이유가 없음 (사용자 확인 — Q2=C).

## 검토했으나 채택하지 않은 대안

### 대안 A: PR 템플릿에 "PR 전 체크리스트" 3항목만 포함
- 무엇: `.github/PULL_REQUEST_TEMPLATE.md`에 체크리스트만 그대로 옮기기
- 왜 안 썼나: "관련 이슈" 섹션이 없으면 PR-이슈 자동 연동(머지 시 이슈 자동 완료)이 실질적으로 동작하지 않음

### 대안 D: 브랜치명 `{사용자명}/mic-{n}-{slug}` (Linear 기본 추천 형식)
- 무엇: Linear가 기본으로 추천하는 `{사용자명}/{identifier}-{slug}` 형식 그대로 사용
- 왜 안 썼나: 기존 `feature/...` 프리픽스 컨벤션과 불일치, "표준"이라는 것 외 추가 이득 없음

## 기존 코드베이스 컨벤션
- 브랜치 네이밍: `feature/{설명}` (예: `feature/frontend-foundation`) — 이번 작업에서 `feature/mic-{n}-{slug}` 형식으로 확장 안내
- 커맨드 파일 구조: `.claude/commands/*.md`. `plan.md`는 전체가 ` ```markdown ``` ` 코드펜스로 감싸진 구조 — 편집 시 이 구조 유지 필요
- PR 체크리스트 원본: `.claude/commands/spec-to-tickets.md`의 "PR 전 체크리스트" 섹션 — 이번에 추가하는 PR 템플릿과 문구 동일하게 유지

## 관련 파일/위치
- `.claude/commands/plan.md` — Step 5 "Linear 연동 후속 처리"에 브랜치 네이밍 규칙 추가
- `.claude/commands/spec-to-tickets.md` — "PR 전 체크리스트" 원본 (Step 3 description 매핑 부근)
- `.github/PULL_REQUEST_TEMPLATE.md` — 신규 생성

## 외부 참조
- Linear-GitHub 연동: 브랜치명/PR 본문에 이슈 식별자가 포함되면 상태 자동 전환 (Linear 표준 동작 — 워크스페이스 설정에 따라 일부 다를 수 있음)
