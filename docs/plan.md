# 계획서: PM 커맨드 신설 (`/project-plan`, `/project-status`) + Linear Project/Milestone 셋업

- 작성일: 2026-06-12
- 관련 이슈/티켓: 없음 (도구/커맨드 체계 확장 작업)

## 목표
전체 요구사항을 기능 단위 백로그로 쪼개 Milestone(M1~M4)에 배치하고 우선순위를 부여해 Linear에 반영하는 `/project-plan`, 그리고 마일스톤별 진행률/다음 우선순위를 보고하는 `/project-status` 커맨드를 신설한다. 이를 위해 Linear에 Project + M1~M4 Milestone을 1회 생성한다.

## 성공 기준
- [ ] Linear에 Project 1개 + Milestone 4개(M1~M4) 생성 확인 (`list_projects(includeMilestones: true)`)
- [ ] `.claude/commands/project-plan.md` 생성 — 입력(전체 요구사항) → 기능 추출 → Milestone 배치 → 우선순위 → 문서 초안 → 승인 → Linear 반영(기능 Issue 생성, sub-issue 연결 안내 포함)
- [ ] `.claude/commands/project-status.md` 생성 — Project/Milestone/Issue 조회 → 마일스톤별 진행률/막힌 이슈/다음 우선순위 후보 보고
- [ ] `.claude/commands/system-design.md`, `spec-draft.md`, `spec-to-tickets.md`에 연계 안내 추가
- [ ] 4개 명령어 파일의 마크다운 구조(Step 순서, 저장 전 승인 패턴 등)가 기존 커맨드 스타일과 일관됨을 육안 확인

## 비범위 (Out of Scope)
- 이번에 `/project-plan` 실제 실행 (기존 9개 이슈를 M1~M4에 배치, 신규 백로그 생성) — 커맨드 작성까지만, 실행은 사용자가 다음에 직접
- Linear Cycle(스프린트) 도입
- Milestone(M1~M4) 구성 자체의 자동 재구성/추가 로직 — `/project-plan`은 "기존 Milestone 조회 후 안 맞으면 사용자에게 새 Milestone 필요 여부를 묻는" 수준까지만

## 단계별 작업 계획

### 단계 1: Linear Project + Milestone(M1~M4) 생성 (1회 셋업)
- 변경 대상: Linear 워크스페이스 (코드 파일 변경 없음)
- 변경 내용 요약: `save_project`로 Project 생성 후 `save_milestone`으로 M1(핵심 트랜잭션 플랫폼)/M2(운영 가시성·신뢰성)/M3(프론트엔드 통합)/M4(성능 검증·스케일링) 4개 Milestone 생성. 실행 직전 Project/Milestone 이름을 사용자에게 확인.
- 검증 방법: `list_projects(includeMilestones: true)`로 Project 1개 + Milestone 4개 확인
- 롤백 방법: Linear UI에서 Project archive (Claude가 직접 archive하지 않고 필요 시 안내)
- 예상 소요: 짧음

### 단계 2: `.claude/commands/project-plan.md` 작성
- 변경 파일: `.claude/commands/project-plan.md` (신규)
- 변경 내용 요약:
  - Step 1: 전체 요구사항 입력(파일 경로 또는 대화) + `docs/system-design.md` 함께 참고
  - Step 2: 기능 단위 추출 (표)
  - Step 3: Milestone 배치 — Linear의 기존 Milestone 목록을 조회해 각 기능을 배치, 맞는 Milestone이 없으면 사용자에게 신규 필요 여부 확인
  - Step 4: 우선순위 부여 (Linear priority 0~4, 기준: MVP/핵심 경로 우선 + 의존관계)
  - Step 5: 문서 초안(`docs/backlog-draft.md` 등) 생성 → 사용자 승인 대기
  - Step 6: 승인 후 Linear Project에 "기능" Issue 생성 — 이 Issue가 추후 `/spec-to-tickets`가 만드는 구현 이슈의 parent가 됨을 명시
- 검증 방법: 마크다운 구조 검토 — Step 순서/번호, 기존 `system-design.md`·`spec-design.md`·`spec-to-tickets.md`의 "저장할까요? (y/n)" 및 "다음 단계 안내" 패턴과 일관되는지 육안 확인
- 롤백 방법: 파일 삭제
- 예상 소요: 보통

### 단계 3: `.claude/commands/project-status.md` 작성
- 변경 파일: `.claude/commands/project-status.md` (신규)
- 변경 내용 요약:
  - Step 1: Project 식별 — 워크스페이스에 Project가 1개면 자동 선택, 여러 개면 사용자에게 질문
  - Step 2: 해당 Project의 Milestone/Issue 조회
  - Step 3: 마일스톤별 진행률(완료/전체), 막힌 이슈(취소·장기 Backlog), 다음 우선순위 후보(미완료 중 priority 높은 순)를 채팅으로 보고 — 문서 저장 안 함
- 검증 방법: 마크다운 구조 검토. (이슈-마일스톤 연관 조회의 정확한 API 형태는 작성 시 Linear MCP 도구 스키마로 확인 — 발견 사항에 기재)
- 롤백 방법: 파일 삭제
- 예상 소요: 보통

### 단계 4: 기존 커맨드 3종 연계 안내 추가
- 변경 파일: `.claude/commands/system-design.md`, `.claude/commands/spec-draft.md`, `.claude/commands/spec-to-tickets.md`
- 변경 내용 요약:
  - `system-design.md` Step 6 안내에 "저장 후 `/project-plan`으로 전체 요구사항을 기능 백로그로 쪼개 Milestone에 배치할 수 있음" 한 줄 추가
  - `spec-draft.md` Step 1에 "Linear 이슈 식별자(`/project-plan`이 생성한 기능 이슈 등)가 주어지면 `get_issue`로 읽어 초안으로 사용한다" 안내 추가
  - `spec-to-tickets.md`에 "관련 기능 이슈(`/project-plan` 결과물)가 있으면, 생성하는 구현 이슈를 그 기능 이슈의 sub-issue(parentId)로 연결한다" 안내 추가
- 검증 방법: 각 파일 수정 부분이 기존 안내 문구 스타일과 일관되는지 육안 확인
- 롤백 방법: 각 파일 git diff 되돌리기
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: `/project-status`가 "마일스톤별 이슈"를 조회하는 정확한 API 형태(이슈 객체에 milestone 필드 포함 여부, `get_milestone`의 반환 내용)가 불확실함 → 대응: 단계 3 작성 시 Linear MCP 도구 스키마를 먼저 확인하고, 단계 1에서 생성한 실제 Milestone으로 샘플 조회해본다.
- 리스크 2: 단계 1(Linear Project/Milestone 생성)은 archive 외에는 되돌리기 번거로운 외부 시스템 변경 → 대응: 실행 직전 이름을 사용자에게 최종 확인.

## 의존성
- 단계 2(`/project-plan`)는 단계 1에서 생성된 Milestone을 "기존 Milestone 조회"의 실제 예시로 참고하므로 단계 1을 먼저 완료한다.
- 단계 4의 `/project-plan` 연계 안내는 단계 2 완료 후 작성한다.

---

## [추가] Label 시스템 구축 + Milestone 재정의 (2026-06-12)

- 배경: 사용자와의 논의 결과, Milestone을 "직무/영역" 기준(기존 M1~M4: 핵심 트랜잭션 플랫폼/운영 가시성·신뢰성/프론트엔드 통합/성능 검증·스케일링)으로 잡는 것은 안티패턴으로 확인됨. Project/Milestone은 "결과·배포" 기준으로 재정의하고, 도메인/직무는 Label(독립된 두 그룹)으로 분리한다.

### 추가 성공 기준
- [ ] Linear Label 생성 확인: 그룹 라벨 "도메인"(하위: 주문/결제/프로모션/유저), "직무"(하위: 백엔드/프론트엔드/인프라) — `list_issue_labels(team: "MIC")`로 확인
- [ ] M1~M4 Milestone이 결과/배포 기준으로 재정의됨을 `list_milestones(project: "프로모션 시스템 구축")`로 확인
- [ ] `project-plan.md`/`spec-to-tickets.md`에 Label 부여 가이드 + 이슈 분해 기준 반영, 구조 일관성 육안 확인

### 단계 5: Linear Label 생성 (도메인/직무 그룹)
- 변경 대상: Linear 워크스페이스 (코드 변경 없음)
- 변경 내용 요약: `mcp__linear__create_issue_label`로 그룹 라벨 "도메인"(`isGroup: true`), "직무"(`isGroup: true`)를 생성한 뒤, 각 그룹의 하위 라벨(`parent: "도메인"` 또는 `parent: "직무"`)을 생성한다.
  - 도메인 하위: 주문, 결제, 프로모션, 유저
  - 직무 하위: 백엔드, 프론트엔드, 인프라
- 검증 방법: `list_issue_labels(team: "MIC")`로 그룹 2개 + 하위 7개, 총 9개 라벨 확인
- 롤백 방법: Linear UI에서 라벨 삭제
- 예상 소요: 짧음
- 실행 직전 라벨명/그룹 구성을 사용자에게 최종 확인한다.

### 단계 6: M1~M4 Milestone 재정의 (결과/배포 기준)
- 변경 대상: Linear (기존 4개 Milestone을 `mcp__linear__save_milestone`의 `id`로 update — 신규 생성 아님)
- 변경 내용 요약: 이름/설명을 다음으로 갱신 (실행 직전 정확한 명칭/설명 사용자 확인)
  - M1. 핵심 기능 종단 간 연동 (MVP) — 프로모션 목록/조회 → 구매 → 주문 상태 확인까지 백엔드+프론트엔드가 연동되어 웹에서 끝까지 동작
  - M2. 운영 안정화 — k6 부하테스트 기반 임계치 확인 + 알림/오토스케일링 튜닝 완료
  - M3. 베타 오픈 / 기능 확장 — 로그인 연동, 마이페이지·주문내역 등 추가 화면 + 프로모션 종류 확장
  - M4. 정식 출시 준비 — 남은 보강 + 운영 문서/배포 절차 정리
- 검증 방법: `list_milestones(project: "프로모션 시스템 구축")`로 4개 이름/설명 갱신 확인
- 롤백 방법: 기존 이름/설명으로 `save_milestone` 재호출 (기존 값: M1 핵심 트랜잭션 플랫폼 / M2 운영 가시성·신뢰성 / M3 프론트엔드 통합 / M4 성능 검증·스케일링 — id는 `docs/context.md` 참고)
- 예상 소요: 짧음

### 단계 7: `project-plan.md` — 도메인 Label 가이드 추가
- 변경 파일: `.claude/commands/project-plan.md`
- 변경 내용 요약: Step 6 Linear 반영 매핑 표에 `labels` 필드 추가 — 기능과 관련된 도메인 Label(들)을 부여한다. 직무 Label은 기능/Epic 단위에서는 보통 여러 직무에 걸치므로 부여하지 않음을 명시.
- 검증 방법: 구조 검토 — 기존 Step 6 표/안내 스타일과 일관되는지 육안 확인
- 롤백 방법: git diff 되돌리기
- 예상 소요: 짧음

### 단계 8: `spec-to-tickets.md` — 이슈 분해 기준 + 도메인/직무 Label 가이드 추가
- 변경 파일: `.claude/commands/spec-to-tickets.md`
- 변경 내용 요약:
  - Step 2(작업 단위 파싱)에 "DB 스키마 설계, CDC/메시지 브로커 연동 등 무거운 선행 작업이 포함되면 별도 이슈로 분리한다" 기준 추가
  - Step 3 매핑 표에 `labels`(도메인 Label 1개 + 직무 Label 1개) 추가
  - Step 4에 `labels` 전달 bullet 추가
- 검증 방법: 구조 검토 — 기존 표/안내 스타일과 일관되는지 육안 확인
- 롤백 방법: git diff 되돌리기
- 예상 소요: 보통

### 추가 리스크 및 대응
- 리스크 3: 단계 6은 기존 Milestone의 이름/설명을 변경 — 아직 연결된 이슈가 없어 데이터 손실 위험은 없으나, 실행 직전 새 이름/설명을 사용자에게 최종 확인.
- 리스크 4: 단계 5의 Label 그룹(`isGroup`/`parent`)이 의도대로 동작하는지 실행 후 `list_issue_labels`로 확인.

### 추가 의존성
- 단계 7, 8은 단계 5에서 생성된 정확한 라벨명을 참고하므로 단계 5를 먼저 완료한다.
- 단계 6(Milestone 재정의)은 단계 5와 독립적으로 실행 가능하다.
