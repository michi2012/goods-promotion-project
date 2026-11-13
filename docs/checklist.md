# 체크리스트: 작업 규칙 정비 (E2E + 백로그)

- 마지막 업데이트: 2026-11-13

## [완료] 범용 e2e skill + 프로젝트 규칙 + 개인 환경 규칙 분리 + 백로그 관리

### 진행 상황
- [x] 단계 1: `.claude/skills/e2e/SKILL.md` 신규 (범용, 사용자 문안 확인 완료)
  - [x] 검증 통과 (프로젝트 고유 명칭 grep 0건, frontmatter name/description 형식)
- [x] 단계 2: `CLAUDE.md` 정비 (사용자 문안 확인 완료)
  - [x] 계획 대비 변경 (사용자 승인): 토큰·맥락 절약을 위해 E2E 프로젝트 적용 값은 `CLAUDE.md` 대신 `.claude/skills/e2e/project.md`(신규)로, `SKILL.md`에 참조 1줄. `CLAUDE.md`에는 "커밋 전 검증" 3줄 + 압축한 "백로그 관리" + skill 지시만
  - [x] 검증 통과 (`git diff --stat CLAUDE.md`: +13 −15, 대상 외 섹션 무변경)
- [x] 단계 3: `docs/backlog.md` 신규 + 메모리 정리 (사용자 문안 확인 완료)
  - [x] 검증 통과 (BL-000~018: 이번 대화의 보고·checklist 발견 사항 대조, 메모리는 백로그 포인터로 변경)
- [x] 단계 4: `CLAUDE.local.md` 신규 (셸 규칙 + Windows 주의사항 4건)
  - [x] 검증 통과 (`git check-ignore` → `.gitignore:53`, `git status` 미표시)

### 최종 검증
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [x] 이 checklist의 발견 사항을 `docs/backlog.md`로 이전 (새 규칙 첫 적용)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `/plan` skill 문서의 Windows 전용 지시 → 백로그 BL-013으로 이전
