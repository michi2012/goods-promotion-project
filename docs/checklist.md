# 체크리스트: v1.7.0 변경 내용을 README.md / docs/port.md에 반영

- 마지막 업데이트: 2026-06-08

## 진행 상황
- [x] 단계 1: README.md "6. 모니터링" 섹션 업데이트 (Tier 표 + 알람 표에 e2e p95 반영)
  - [x] 검증 통과 (grep으로 신규 문구 확인, 표 컬럼 수 일치)
- [x] 단계 1-1: README.md에 AI 개발 워크플로우 섹션 신설 (CLAUDE.md/Skills/슬래시 명령어/Linear MCP 간략 소개 + port.md 링크)
  - [x] 검증 통과 (grep으로 신규 섹션 확인)
- [x] 단계 2: docs/port.md 모니터링 섹션 업데이트 (알람 표에 `PaymentE2ELatencyHigh` 추가)
  - [x] 검증 통과 (grep으로 신규 문구 확인)
- [x] 단계 3: docs/port.md AI 워크플로우 섹션 업데이트 (`pr`/`spec-draft`/`spec-to-tickets` 추가, Linear MCP 연동 서술 보강)
  - [x] 검증 통과 (grep으로 신규 행 확인)

## 최종 검증
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 — design-notes.md/arch-snapshot.md/CHANGELOG.md 미변경
- [x] `git status`로 변경 파일 확인 — README.md, docs/plan.md, docs/context.md, docs/checklist.md (docs/port.md는 .gitignore 대상이라 git diff에 안 잡힘, 디스크 변경은 grep으로 확인)
- [x] 표 마크다운이 깨지지 않았는지 육안 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `docs/port.md`는 `.gitignore:55`에 의해 추적 제외 대상임을 확인 — 의도된 설정으로 보여 손대지 않음 (포트폴리오용 비공개 콘텐츠로 추정)
- README.md AI 워크플로우 섹션에 추가했던 `[docs/port.md](docs/port.md)` 링크 줄을 린터가 자동 제거함 — port.md가 gitignore 대상이라 공개 저장소에서는 깨진 링크가 되므로 타당한 조치로 판단, 되돌리지 않음
