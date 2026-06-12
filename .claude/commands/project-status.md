---
description: Linear Project의 마일스톤별 진행률/막힌 이슈/다음 우선순위 후보를 채팅으로 보고. 문서 저장 없음.
---

# /project-status — 마일스톤별 진행 현황 보고

Linear Project의 Milestone/Issue 현황을 조회해 마일스톤별 진행률, 막힌 이슈, 다음에 진행할 우선순위 후보를 채팅으로 보고한다. 문서를 생성하지 않으며, 반복 실행을 전제로 한다.

**사용법**
- `/project-status` — 워크스페이스의 Project 현황을 보고한다 (Project가 여러 개면 Step 1에서 선택)

---

## Step 1. Project 식별

`mcp__linear__list_projects`로 워크스페이스의 Project 목록을 조회한다.

- Project가 1개면 자동으로 선택한다.
- 여러 개면 사용자에게 어떤 Project를 조회할지 묻는다.
- Project가 0개면 "`/project-plan`을 먼저 실행해 Project/Milestone을 셋업하세요"라고 안내하고 종료한다.

---

## Step 2. Milestone/Issue 조회

선택한 Project에 대해 다음을 조회한다.

- `mcp__linear__list_milestones(project: "{Project명}")` → Milestone 목록 (이름, 설명, `progress`)
- `mcp__linear__list_issues(project: "{Project명}", includeArchived: true)` → Project에 속한 이슈 전체. `hasNextPage`가 true이면 반환된 `cursor`로 다음 페이지를 이어서 조회한다.

각 이슈 객체에 `milestone` 필드가 포함되어 있으면 그 값으로 이슈를 Milestone별로 그룹화한다.
이슈 객체에 `milestone` 필드가 없으면, Milestone별 이슈 그룹화 대신 `list_milestones`가 반환한 `progress`(0~1)를 마일스톤별 진행률로 사용하고, "막힌 이슈"/"다음 우선순위 후보"는 Project 전체 이슈 기준으로 보고한다.

---

## Step 3. 진행 현황 보고

채팅으로 다음 형식으로 보고한다 (문서로 저장하지 않음).

```
## 프로젝트 현황: {Project명}

### 마일스톤별 진행률
| Milestone | 진행률 | 완료 / 전체 |
|---|---|---|
| {Milestone명} | {progress 또는 완료 비율}% | {n}/{m} (이슈별 milestone 필드가 없으면 "-") |

### 막힌 이슈
상태가 `Canceled`인 이슈, 또는 상태가 `Backlog`이고 `updatedAt`이 2주 이상 지난 이슈("장기 Backlog")를 나열한다.

| 이슈 | 상태 | 사유 추정 |
|---|---|---|
| {식별자} {제목} | {status} | {Canceled 또는 장기 Backlog} |

(없으면 "없음")

### 다음 우선순위 후보
미완료 이슈(`statusType`이 completed/canceled가 아닌 것) 중 `priority`가 높은(0=None 제외, 숫자가 작을수록 높음) 순서로 상위 5개.

| 식별자 | 제목 | priority | Milestone |
|---|---|---|---|
| {식별자} | {제목} | {priority명} | {Milestone명 또는 "-"} |
```

다음 우선순위 후보 중 작업을 시작할 이슈가 있으면 `/plan {식별자}`로 착수할 수 있음을 안내한다.
