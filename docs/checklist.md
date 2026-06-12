# 체크리스트: PM 커맨드 신설 (`/project-plan`, `/project-status`)

- 마지막 업데이트: 2026-06-12

## 진행 상황
- [x] 단계 1: Linear Project + Milestone(M1~M4) 생성
  - [x] 검증 통과 (`list_milestones(project: "프로모션 시스템 구축")` — `list_projects(includeMilestones: true)`는 400 에러로 대체)
  - [x] 코드리뷰 통과 (코드 변경 없음, N/A)
- [x] 단계 2: `.claude/commands/project-plan.md` 작성
  - [x] 검증 통과 (구조 검토 — Step 1~6, `---` 구분, "저장할까요? (y/n)", 다음 단계 안내 패턴 일관)
  - [x] 코드리뷰 통과
- [x] 단계 3: `.claude/commands/project-status.md` 작성
  - [x] 검증 통과 (구조 검토 — Step 1~3, `---` 구분, 다음 단계 안내 패턴 일관)
  - [x] 코드리뷰 통과
- [x] 단계 4: `system-design.md`/`spec-draft.md`/`spec-to-tickets.md` 연계 안내 추가
  - [x] 검증 통과 (육안 확인 — 각 파일 기존 안내 문구 스타일과 일관)
  - [x] 코드리뷰 통과

## 최종 검증
- [ ] 4개 명령어 파일 마크다운 구조/스타일 일관성 확인
- [x] Linear Project 1개 + Milestone 4개 생성 확인
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (`/project-plan` 실제 실행 안 함, Cycle 미도입)
- [ ] git diff로 의도하지 않은 파일 변경이 없는지 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `/project-status`의 "마일스톤별 이슈" 조회 API 형태(이슈에 milestone 필드 포함 여부 등) — `list_issues`에 milestone 필터는 없고, 기존 이슈(MIC-1~9)는 Project/Milestone에 연결되어 있지 않아 issue 객체에 milestone 필드가 포함되는지 샘플로 확인 불가. `project-status.md`에 "milestone 필드 있으면 그룹화, 없으면 list_milestones의 progress로 대체" 분기로 작성함 — `/project-plan` 실제 실행 후 실제 이슈로 재확인 권장 (이번 작업 비범위)
- `list_projects(includeMilestones: true)`가 400 에러를 반환함 (한글 이름/쿼리 여부와 무관, 파라미터 없이도 재현). 대신 `list_milestones(project: "<name>")`로 마일스톤 목록 조회 가능 — `/project-status`/`/project-plan`에서 마일스톤 조회 시 `list_milestones` 사용 권장

---

## [진행 중] Label 시스템 구축 + Milestone 재정의

## 진행 상황 (추가)
- [x] 단계 5: Linear Label 생성 (도메인/직무 그룹)
  - [x] 검증 통과 (`list_issue_labels(team: "MIC")` — 도메인 하위 4개(주문/결제/프로모션/유저), 직무 하위 3개(백엔드/프론트엔드/인프라) 모두 parent로 그룹화 확인)
  - [x] 코드리뷰 통과 (코드 변경 없음, N/A)
- [x] 단계 6: M1~M4 Milestone 재정의 (결과/배포 기준)
  - [x] 검증 통과 (`list_milestones(project: "프로모션 시스템 구축")` — M1~M4 이름/설명 모두 갱신 확인)
  - [x] 코드리뷰 통과 (코드 변경 없음, N/A)
- [x] 단계 7: `project-plan.md` 도메인 Label 가이드 추가
  - [x] 검증 통과 (구조 검토 — Step 6 표에 `labels` 행 추가, 기존 표 스타일과 일관)
  - [x] 코드리뷰 통과
- [x] 단계 8: `spec-to-tickets.md` 이슈 분해 기준 + 도메인/직무 Label 가이드 추가
  - [x] 검증 통과 (구조 검토 — Step 2 분해 기준 bullet, Step 3 `labels` 행, Step 4 전달 bullet 모두 기존 스타일과 일관)
  - [x] 코드리뷰 통과

## 최종 검증 (추가)
- [x] Linear Label 그룹 2개("도메인", "직무") + 하위 라벨 7개 생성 확인
- [x] M1~M4 Milestone 이름/설명이 결과·배포 기준으로 갱신됨 확인
- [x] `project-plan.md`/`spec-to-tickets.md` 마크다운 구조가 기존 스타일과 일관됨을 육안 확인 (코드리뷰 agent APPROVE)
- [x] git diff로 의도하지 않은 파일 변경이 없는지 확인 (`git status --porcelain` — 의도한 8개 파일만 변경: 신규 2개 + 수정 6개)

## 발견 사항 (추가)
- 코드리뷰(agent) 권장 사항 3건 — 모두 선택사항(APPROVE, 머지 차단 아님)이었으나 사용자 요청으로 모두 적용함:
  1. `project-plan.md` Step 6 `labels` 설명에 직무 Label 후보(백엔드/프론트엔드/인프라) 추가 명시
  2. `project-status.md` "막힌 이슈"에 "장기 Backlog" 판단 기준(`updatedAt` 2주 이상) 추가
  3. `spec-to-tickets.md` Step 3 미리보기 템플릿에 `**Labels**`/`**Parent**` 줄 추가

---

## [진행 중] Linked Issues(blockedBy) + PR 전 체크리스트 + /plan 반영 규칙

`/plan` 커맨드 없이 진행 (문서 수정만, 사용자 승인).

- [x] `project-plan.md` Step 6에 `blockedBy` 필드 추가 — Step 4의 "의존 기능"을 description 텍스트 대신 Linear 네이티브 관계(`blockedBy`)로 연결. 의존 대상을 먼저 생성하도록 안내 추가, 기존 "의존: {기능명}" description 텍스트 언급은 제거(중복 방지)
  - [x] 검증 통과 (구조 검토 — 표 형식 일관, `save_issue`의 `blockedBy` 파라미터 존재 확인됨)
- [x] `spec-to-tickets.md` Step 3에 "PR 전 체크리스트"(테스트 통과/API 문서 갱신/민감정보 노출 확인) 고정 description 섹션 추가, 미리보기 템플릿에도 반영
  - [x] 검증 통과 (구조 검토 — 기존 표/템플릿 스타일과 일관)
- [x] `.claude/commands/plan.md` Step 1-0에 "이슈 description의 'PR 전 체크리스트' 섹션을 `docs/checklist.md`의 '최종 검증'에 그대로 포함한다" 규칙 추가 (당초 "성공 기준"에 포함하는 방식이었으나, 범용 품질 게이트(테스트/문서/보안)와 기능별 완료조건이 섞여 모호해진다는 점을 발견해 "최종 검증"으로 변경 — `spec-to-tickets.md`의 안내 문구도 동기화)
  - [x] 검증 통과 (구조 검토 — 기존 bullet 스타일과 일관)

### 발견 사항
- Step 1-0의 일반 규칙("이슈 title·description을 성공 기준 초안의 출발점으로 삼는다")이 "PR 전 체크리스트" 섹션도 description의 일부로 끌어와, 같은 항목이 "성공 기준"(plan.md)과 "최종 검증"(checklist.md) 양쪽에 중복될 위험을 발견 → "PR 전 체크리스트" 섹션은 성공 기준 초안 출발점에서 제외하고, 최종 검증 템플릿의 기존 항목과 중복되면 유지하도록 규칙 보완
