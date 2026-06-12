# 계획서: 브랜치 네이밍 규칙 명시 + GitHub PR 템플릿 동기화

- 작성일: 2026-06-12
- 관련 이슈/티켓: 없음 (Linear 이슈 미연동)

## 목표
`/plan`에 Linear-GitHub 자동 연동(브랜치명 기반 이슈 상태 전환)이 실제로 동작하기 위한 브랜치 네이밍 규칙을 명시하고, `/spec-to-tickets`의 "PR 전 체크리스트"를 GitHub PR 템플릿에도 동기화해 PR 작성 시점에 노출되게 한다.

## 성공 기준
- [ ] `.claude/commands/plan.md` Step 5(Linear 연동 후속 처리)에 브랜치 네이밍 규칙(`feature/mic-{n}-{slug}` 형식) 문구 추가 (구조 검토로 확인)
- [ ] `.github/PULL_REQUEST_TEMPLATE.md` 신규 생성 — "변경 요약" / "관련 이슈(Closes MIC-XX)" / "PR 전 체크리스트" 섹션 포함 (파일 존재 + 내용 확인)
- [ ] PR 템플릿의 "PR 전 체크리스트" 문구가 `spec-to-tickets.md`의 해당 섹션과 동일한지 확인
- [ ] `git status`/`git diff --stat`으로 의도한 2개 파일만 변경됐는지 확인

## 비범위 (Out of Scope)
- 기존 브랜치(`feature/frontend-foundation`)의 이름 변경 — 신규 브랜치부터 적용
- CI/CD 워크플로우 파일 추가
- `/project-plan`, `/spec-to-tickets` 등 다른 커맨드 파일 수정
- Linear 워크스페이스 설정(브랜치 매칭 정규식 등) 변경 — Claude가 변경할 수 없는 영역

## 단계별 작업 계획 (최대 7단계)

### 단계 1: `.claude/commands/plan.md`에 브랜치 네이밍 규칙 추가
- 변경 파일: `.claude/commands/plan.md`
- 변경 내용 요약: Step 5 "Linear 연동 후속 처리" 섹션에 "이 자동 연동이 동작하려면 브랜치명에 이슈 식별자가 포함되어야 한다 — `feature/mic-{n}-{설명}` 형식(예: `feature/mic-15-promotion-api`)으로 브랜치를 생성하도록 안내한다"는 내용을 추가.
- 검증 방법: 구조 검토 — 기존 Step 5 문구 스타일과 일관되는지 육안 확인
- 롤백 방법: git checkout으로 해당 라인 되돌리기
- 예상 소요: 짧음

### 단계 2: `.github/PULL_REQUEST_TEMPLATE.md` 신규 생성
- 변경 파일: `.github/PULL_REQUEST_TEMPLATE.md` (신규, `.github/` 디렉토리도 함께 생성)
- 변경 내용 요약: "변경 요약", "관련 이슈(Closes MIC-XX)", "PR 전 체크리스트"(테스트 작성 및 통과 확인 / API·인터페이스 변경 시 관련 문서 갱신 / 민감 정보 로그·응답 노출 여부 확인) 섹션 포함. 체크리스트 문구는 `spec-to-tickets.md`와 동일하게 맞춘다.
- 검증 방법: 구조 검토 — `spec-to-tickets.md`의 "PR 전 체크리스트" 문구와 비교
- 롤백 방법: 파일/디렉토리 삭제
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: Linear의 실제 브랜치 매칭 규칙은 워크스페이스 설정에 따라 다를 수 있음 → 대응: 단정적 표현 대신 "권장 형식"으로 안내
- 리스크 2: PR 템플릿이 자동 적용되려면 경로가 정확해야 함 → 대응: GitHub 표준 경로(`.github/PULL_REQUEST_TEMPLATE.md`) 사용

## 의존성
없음 (독립적인 문서/설정 추가)
