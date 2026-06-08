# /pr — Pull Request 생성

현재 브랜치의 변경 사항을 바탕으로 PR을 생성하고, 관련 Linear 이슈와 연결한다.

**사용법**
- `/pr` — 현재 브랜치 → main 브랜치로 PR 생성 (브랜치명/커밋에서 Linear 식별자 자동 추출)
- `/pr {Linear 식별자}` — 이슈를 명시적으로 지정 (자동 추출이 안 될 때)

---

## Step 1. 변경 사항 및 관련 이슈 파악

```powershell
git status
git diff main...HEAD --stat
git log main..HEAD --oneline
```

현재 브랜치명과 커밋 메시지에서 Linear 식별자(`MIC-\d+`)를 추출한다. 여러 개 발견되면 모두 후보로 제시한다. 못 찾으면 사용자에게 묻는다 ("없음"도 가능).

---

## Step 2. PR 제목/본문 초안 작성 및 미리보기

`main..HEAD` 커밋 히스토리를 분석해 제목(70자 이내)과 본문을 작성한다.

이슈 식별자를 찾았다면 본문에 GitHub-Linear 자동 연동의 매직 워드(`Closes {식별자}`)를 포함한다 — PR이 머지되면 해당 이슈 상태가 자동으로 전환된다 (수동으로 상태를 바꾸지 않는다).

```
## Summary
- {무엇이 바뀌었는지 — 파일/명령어/구조 단위로 구체적으로 1-3줄}

## How this was tested
- {어떻게 검증했는지 — 실행한 시나리오/명령어를 서술형으로}

Closes MIC-{n}
```

```
## PR 미리보기

**Title**: {제목}
**Base**: main ← **Head**: {현재 브랜치}
**Body**:
{본문 — 식별자 없으면 "Closes" 줄 생략}

이대로 PR을 생성할까요? (y/n)
```

---

## Step 3. 생성

사용자가 승인하면 다음을 순서대로 실행한다:

```powershell
git push -u origin HEAD
gh pr create --title "{제목}" --body "{본문}"
```

생성된 PR URL을 출력한다.

PR 머지는 사용자 또는 관리자가 직접 수행한다 — 이 명령어는 PR 생성까지만 담당하며, 머지를 절대 실행하지 않는다.

---

## Step 4. Linear 연동 — 이슈에 PR 링크 코멘트

Step 1에서 식별자를 찾은 경우에만 진행한다.

`mcp__linear__save_comment(id={식별자}, body="관련 PR: {PR URL}")`를 호출해 PR 링크를 코멘트로 남긴다.

상태 전환(`In Review` → `Done` 등)은 GitHub-Linear 자동 연동(PR 생성/머지 시 트리거)에 맡기고, 이 명령어에서는 절대 수동으로 변경하지 않는다.
