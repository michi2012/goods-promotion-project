---
description: 전체 요구사항을 기능 단위 백로그로 쪼개 Milestone에 배치하고 우선순위를 부여해 Linear에 반영.
---

# /project-plan — 전체 요구사항 → 기능 백로그 + Milestone 배치 + Linear 반영

전체 요구사항(또는 새로 추가된 요구사항 묶음)을 입력받아 기능 단위로 쪼개고, Linear Project의 기존 Milestone에 배치하고 우선순위를 부여한 뒤 문서 초안으로 정리한다. 승인 후 각 기능을 Linear "기능" Issue로 생성한다.

이 Issue는 추후 `/spec-draft → /spec-to-tickets`가 만드는 구현 이슈의 **부모(parent)**가 된다 — 전체 백로그(이 커맨드) → 기능 명세(`/spec-draft` 등) → 구현 이슈(`/spec-to-tickets`) 순서로 세분화된다.

프로젝트 시작 시점에 한 번 실행하거나, 새로운 요구사항 묶음이 생길 때마다 다시 실행할 수 있다 — Milestone은 미리 정의된 것을 조회해 배치하므로 매번 새로 만들지 않는다.

**사용법**
- `/project-plan {요구사항 개요 경로}` — 해당 문서를 읽어 백로그를 도출한다
- `/project-plan` — 대화 중 붙여넣은 요구사항 개요로 도출한다

---

## Step 1. 입력 받기

파일 경로가 주어지면 해당 파일을 읽는다. 그렇지 않으면 사용자가 붙여넣은 내용을 요구사항 개요로 사용한다.
요구사항 개요가 없으면 사용자에게 요청한다.

`docs/system-design.md`가 존재하면 함께 읽는다 — 식별된 엔티티/서비스 경계를 참고해 기능을 서비스 단위로 묶는 데 활용한다.

---

## Step 2. 기능 단위 추출

요구사항 개요에서 사용자에게 가치를 주는 단위(기능)를 추출한다. 너무 세분화하지 않는다 — 한 기능은 보통 `/spec-draft` 1회 분량(User Story 1~3개) 정도가 적절하다.

```markdown
| 기능 | 한 줄 설명 | 관련 서비스/모듈 |
|---|---|---|
| {기능명} | {무엇을 가능하게 하는가} | {`docs/system-design.md`의 서비스/모듈 — 식별 불가 시 "미정"} |
```

---

## Step 3. Milestone 배치

먼저 Project를 식별한다 — `mcp__linear__list_projects`로 조회해 워크스페이스에 Project가 1개면 자동으로 사용하고, 여러 개면 사용자에게 묻는다.

`mcp__linear__list_milestones(project: "{Project명}")`로 기존 Milestone 목록을 조회한다.

각 기능을 가장 적합한 Milestone에 배치한다.

- 적합한 Milestone이 없으면, 새 Milestone이 필요한지 사용자에게 묻는다 (이 커맨드는 새 Milestone을 자동 생성하지 않는다 — "기존 Milestone 조회 후 배치"까지만 담당한다).

```markdown
| 기능 | 배치 Milestone |
|---|---|
| {기능명} | {Milestone명} |
```

---

## Step 4. 우선순위 부여

각 기능에 Linear `priority`(0=None, 1=Urgent, 2=High, 3=Medium, 4=Low)를 부여한다.

기준:
- MVP/핵심 경로(다른 기능이 의존하는 기반 기능)일수록 높은 우선순위(1~2)를 부여한다.
- 다른 기능에 의존하는 기능은 의존 대상보다 낮은 우선순위를 부여한다.
- 의존 관계가 있다면 표에 함께 표시한다.

```markdown
| 기능 | Milestone | priority | 의존 기능 |
|---|---|---|---|
| {기능명} | {Milestone명} | {0~4} | {있다면 기능명, 없으면 "-"} |
```

---

## Step 5. 백로그 초안 생성 및 저장

Step 2~4의 결과를 합친다.

```markdown
# 백로그 초안: {프로젝트/요구사항명}

- 작성일: YYYY-MM-DD
- 관련 요구사항: {경로 또는 "대화 중 입력"}
- Linear Project: {Project명}

## 기능 백로그

| 기능 | 설명 | 관련 서비스/모듈 | Milestone | priority | 의존 기능 |
|---|---|---|---|---|---|
| {기능명} | {설명} | {서비스/모듈} | {Milestone명} | {0~4} | {의존 기능 또는 "-"} |

가정: {불명확해서 임의로 채운 부분이 있다면 명시}
```

저장 경로를 제안한다.

```
docs/backlog-draft.md
```

`docs/backlog-draft.md`가 이미 있으면 기존 내용을 덮어쓰지 않고 새 섹션(`## 기능 백로그 (추가: YYYY-MM-DD)`)으로 추가할 것을 제안한다.

저장할까요? (y/n)

---

## Step 6. Linear 반영

사용자가 승인하면 각 기능을 Linear 이슈로 생성한다. `mcp__linear__save_issue`를 기능마다 순서대로 호출한다 (생성이므로 `id`는 전달하지 않는다).

의존 관계가 있는 기능은 의존 대상(선행 기능)을 먼저 생성한다 — 그 식별자를 의존하는 기능의 `blockedBy`로 전달하기 위함.

| 필드 | 값 |
|---|---|
| title | {기능명} |
| description | {한 줄 설명} |
| team | `MIC` |
| project | {Project명} |
| milestone | Step 3에서 배치한 Milestone명 |
| priority | Step 4에서 부여한 값 |
| labels | 기능과 관련된 도메인 Label(들) — 주문/결제/프로모션/유저 중 해당하는 것 (직무 Label인 백엔드/프론트엔드/인프라는 기능 단위가 보통 여러 직무에 걸치므로 부여하지 않는다) |
| blockedBy | Step 4에서 식별한 의존 기능이 있으면, 먼저 생성된 그 기능 이슈의 Linear 식별자 (없으면 생략) |

생성이 끝나면 결과를 표로 정리해 출력한다.

```
## 생성 완료

| 식별자 | 제목 | Milestone | URL |
|---|---|---|---|
| MIC-{n} | {제목} | {Milestone명} | {url} |
```

이 이슈는 추후 `/spec-draft {식별자}`로 명세를 다듬고 `/spec-to-tickets`로 구현 이슈를 생성할 때, 그 구현 이슈의 parent(`parentId`)로 연결할 수 있음을 안내한다.
전체 진행 현황은 이후 `/project-status`로 확인할 수 있음을 안내한다.
