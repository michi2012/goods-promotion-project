# 체크리스트: 브랜치 네이밍 규칙 명시 + GitHub PR 템플릿 동기화

- 마지막 업데이트: 2026-06-12

## 진행 상황
- [x] 단계 1: `.claude/commands/plan.md` Step 5에 브랜치 네이밍 규칙 추가
  - [x] 검증 통과 (구조 검토 — 기존 Step 5 문구 스타일과 일관, ```markdown 코드펜스 구조 유지 확인)
  - [x] 코드리뷰 통과 (자체 검토 — 2줄 추가뿐인 경량 변경, agent 생략)
- [x] 단계 2: `.github/PULL_REQUEST_TEMPLATE.md` 신규 생성
  - [x] 검증 통과 (`spec-to-tickets.md`의 "PR 전 체크리스트" 문구와 동일함을 확인)
  - [x] 코드리뷰 통과 (자체 검토 — 신규 템플릿 파일, agent 생략)

## 최종 검증
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (브랜치 이름 변경/CI 워크플로우/다른 커맨드 파일 수정/Linear 워크스페이스 설정 변경 없음)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (`git status --porcelain` — `.claude/commands/plan.md`, `.github/PULL_REQUEST_TEMPLATE.md`, docs 3종만 변경)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (없음)

---

## [완료] PR 템플릿 "테스트 방법"/"영향도" 섹션 + /plan Step 6-4 보강

`/plan` 없이 진행 (사용자 승인 — 소규모 템플릿/문서 수정).

- [x] `.github/PULL_REQUEST_TEMPLATE.md`에 "테스트 방법"/"영향도 및 주의사항" 섹션 추가 ("관련 이슈"와 "PR 전 체크리스트" 사이)
  - [x] 검증 통과 (구조 검토 — 기존 섹션 스타일과 일관)
- [x] `.claude/commands/plan.md` Step 6-4 종합 보고서 항목에 "영향도" 불릿 추가
  - [x] 검증 통과 (구조 검토 — ```markdown 코드펜스 구조 유지 확인)

### 발견 사항
- (없음)
