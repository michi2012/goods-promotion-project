# 맥락 노트: PM 커맨드 신설 (`/project-plan`, `/project-status`)

## 왜 이 방식을 선택했는가
- 기존 커맨드 파이프라인(`/spec-draft → /system-design(1회) → /spec-design(선택) → /spec-to-tickets → /plan`)은 전부 "기능 1개" 단위로 동작한다. "전체 프로젝트가 무엇을, 어떤 순서로, 얼마나 진행됐는지"를 보는 도구가 없었다.
- `/system-design`(시스템 구조: 엔티티/서비스 경계/ERD 뼈대)과 `/project-plan`(백로그: 기능 목록 + Milestone 배치 + 우선순위)을 분리했다. 둘은 같은 "전체 요구사항"을 입력으로 받지만 책임이 다르다(구조 vs 일정·우선순위) — 하나로 합치면 책임이 섞인다.
- Milestone(M1 핵심 트랜잭션 플랫폼 / M2 운영 가시성·신뢰성 / M3 프론트엔드 통합 / M4 성능 검증·스케일링)은 이 프로젝트의 현재 아키텍처(서버A/B/C Saga+CQRS, K8s/Istio, 모니터링/AIOps, 막 시작한 프론트엔드)를 기준으로 "capability 영역" 단위로 정했다. SDLC 단계(설계→구현→배포)가 아닌 영역별 분류를 택한 이유는 각 영역이 동시에/반복적으로 진행되기 때문이다.
- Milestone 정의를 `/project-plan` 커맨드 로직에 하드코딩하지 않고 Linear에 1회 생성 후 "기존 Milestone 조회 → 배치"로 설계했다 — 커맨드가 이 프로젝트에 종속되지 않게 유지하기 위함.
- `/project-plan` 출력을 "문서 초안 → 승인 → Linear 반영" 2단계로 했다 — Linear에 대량 이슈/마일스톤 배치를 만드는 것은 archive 외에는 되돌리기 어려운 외부 시스템 변경이라 검토 단계를 둔다.
- `/spec-to-tickets`가 만드는 구현 이슈를 `/project-plan`이 만든 기능 이슈의 sub-issue(parentId)로 연결하기로 했다 — 기존 워크스페이스에 이미 MIC-8(부모)→MIC-9(자식) 같은 parent-child 패턴이 있어 일관성을 유지한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: `/project-plan`을 `/system-design`에 통합
- 무엇: 시스템 구조 설계와 백로그/우선순위 산출을 한 커맨드에서 처리
- 왜 안 썼나: 책임이 다르다(구조 vs 일정). 분리해야 재사용성과 가독성이 좋다.

### 대안 B: Milestone(M1~M4)을 `/project-plan` 내부 로직에 하드코딩
- 무엇: 커맨드 실행 시마다 M1~M4를 코드에 명시된 값으로 사용
- 왜 안 썼나: 다른 프로젝트나 새 도메인 추가 시 재사용 불가. Linear의 실제 Milestone을 조회하는 방식이 더 범용적이다.

### 대안 C: `/project-plan` 결과를 Linear에 즉시 반영(1단계)
- 무엇: 문서 초안 없이 바로 Project에 기능 Issue 생성
- 왜 안 썼나: 대량 생성/배치가 잘못되면 되돌리기 번거롭다. 검토 단계가 안전하다.

### 대안 D: Linear Cycle(스프린트) 도입
- 무엇: Milestone 내부 일정을 Cycle로 타임박싱
- 왜 안 썼나: Milestone에 속한 이슈의 priority/dueDate로 충분하다. Cycle은 실제로 타임박싱이 필요해지면 추후 도입.

## 기존 코드베이스 컨벤션
- 커맨드 파일 위치/형식: `.claude/commands/*.md`, frontmatter `description:` + `# /명령어 — 한줄설명` + `## Step N. ...` 구조 (참고: `system-design.md`, `spec-to-tickets.md`, `k6-test.md`)
- "저장할까요? (y/n)" 패턴으로 사용자 승인 후 파일 생성 (참고: `spec-design.md` Step 5, `spec-draft.md` Step 4)
- "다음 단계 안내" 패턴: 각 커맨드 마지막에 다음으로 실행 가능한 커맨드를 한 줄로 안내 (참고: `spec-draft.md` Step 4 마지막 줄, `system-design.md` Step 6 마지막 줄)

## 관련 파일/위치
- `.claude/commands/system-design.md` — 시스템 전체 엔티티/서비스 경계 뼈대(1회), Step 6에 `/project-plan` 안내 추가
- `.claude/commands/spec-draft.md` — 명세서 다듬기, Step 1에 Linear 이슈 입력 지원 추가
- `.claude/commands/spec-to-tickets.md` — 구현 이슈 생성, sub-issue 연결 안내 추가
- `.claude/commands/spec-design.md`, `.claude/commands/plan.md`, `.claude/commands/k6-test.md` — 참고용 기존 패턴 (변경 없음)

## 외부 참조
- Linear 워크스페이스: 팀 "Michi2012" (teamId: `aab03cf6-0c6c-4a56-a551-225fca2542cf`), 현재 Project 0개, 이슈 9개(MIC-1~9). 기존 parent-child 패턴: MIC-8(완료)→MIC-9(후속), MIC-5(취소)→MIC-6/7(취소)

---

## [추가] Label 시스템 구축 + Milestone 재정의 (2026-06-12)

### 왜 이 방식을 선택했는가
- 기존 M1~M4(핵심 트랜잭션 플랫폼 / 운영 가시성·신뢰성 / 프론트엔드 통합 / 성능 검증·스케일링)는 "capability 영역" 또는 "직무" 기준으로 정했는데, 사용자와의 논의 결과 이는 실무 안티패턴으로 확인됨 — 특히 M3("프론트엔드 통합")는 직무 단위 분리 그 자체이고, M2/M4는 "완료" 시점이 없는 영속적 영역이라 진행률(progress)이 의미를 갖기 어렵다.
- 현업 기준(사용자와 합의된 일반 규칙, 다른 프로젝트에도 적용 가능): Project = 독립적으로 배포·평가 가능한 결과물 단위. Milestone = 그 Project 목표를 향한 시간/완성도 체크포인트(버전, 배포 시점, 핵심 기능 완성 단계). 양쪽 모두 기준은 "도메인"이 아니라 "결과/시간"이다. 도메인(주문/결제/프로모션/유저)과 직무(백엔드/프론트엔드/인프라)는 어느 레벨에서도 1차 축이 아니라 Label(직교 축)로 부여한다. 예외: 그 "도메인"이 실질적으로 독립 배포 단위(별도 제품/서비스)라면 도메인=Project도 가능 — 핵심 판단 기준은 "독립 배포/평가 단위인가".
- 위 규칙에 따라 M1~M4를 "결과/배포" 기준으로 재정의한다: M1=핵심 기능 종단 간 연동(MVP, 백+프론트 전체 동작) / M2=운영 안정화(k6 임계치+알림/오토스케일링) / M3=베타 오픈·기능 확장 / M4=정식 출시 준비.
- Label을 "도메인"/"직무" 두 개의 독립된 그룹(`isGroup: true` + `parent`)으로 만들어, 마일스톤 진척도·병목 직무·도메인별 안정성을 필터 조합으로 진단할 수 있게 한다 (예: "M1 + 백엔드"로 백엔드 잔여 작업만 보기, "결제 + 전체 마일스톤"으로 결제 도메인 누적 작업량 보기).
- `/spec-to-tickets`가 만드는 구현 이슈(예: "프로모션 대상자 자격 검증 API")에는 도메인+직무 Label을 모두 부여한다 — 구현 이슈는 보통 하나의 도메인·하나의 직무에 속한다. `/project-plan`이 만드는 기능/Epic 이슈에는 도메인 Label만 부여한다 — 기능 단위는 보통 여러 직무(백+프론트)에 걸치므로 직무 Label을 강제하지 않는다.
- 이슈 분해 기준을 추가한다: DB 스키마 설계, CDC/메시지 브로커 연동 같은 무거운 선행 작업은 해당 API 이슈에 묶지 않고 별도 이슈로 분리한다 — 진행률 추적의 정확도를 높이기 위함.

### 검토했으나 채택하지 않은 대안

### 대안 E: 도메인을 Project로, 기능을 Milestone으로
- 무엇: Project를 "주문 도메인"/"결제 도메인" 등으로 나누고, 그 안에 기능 단위 Milestone을 둔다.
- 왜 안 썼나: 이 프로젝트는 하나의 사용자 대면 서비스이고 도메인이 독립 배포 단위가 아니다. 이렇게 나누면 "이 제품이 전체적으로 동작하는가"를 보여줄 Project 레벨이 사라지고, 직무별 Milestone과 동일한 문제(부분 완료가 "끝"으로 보이는 문제)가 한 레벨 위에서 재발한다.

## 관련 파일/위치 (추가)
- `.claude/commands/project-plan.md` — Step 6 매핑 표에 도메인 Label 부여 가이드 추가
- `.claude/commands/spec-to-tickets.md` — Step 2에 이슈 분해 기준, Step 3/4에 도메인+직무 Label 부여 가이드 추가

## 외부 참조 (추가)
- Linear Label 그룹(신설 예정): "도메인"(하위: 주문/결제/프로모션/유저), "직무"(하위: 백엔드/프론트엔드/인프라) — `create_issue_label`의 `isGroup`/`parent` 파라미터로 그룹화. 기존 라벨(Improvement/Feature/Bug)과 이름 충돌 없음 (`list_issue_labels(team: "MIC")`로 확인).
- 기존 Milestone(변경 전, id로 update 예정): M1 핵심 트랜잭션 플랫폼(`2519bee9-80f8-4f2c-a4b5-b6bb65309501`) / M2 운영 가시성·신뢰성(`aea299b9-6ba3-4ba0-bb58-de7a8f12b533`) / M3 프론트엔드 통합(`c139c886-d8a5-4998-a3ad-f6b3628040c4`) / M4 성능 검증·스케일링(`6da2760b-c1b8-4bf3-9a73-6ee3373ee278`)
