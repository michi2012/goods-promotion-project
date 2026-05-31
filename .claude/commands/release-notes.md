# /release-notes — 릴리즈 노트 생성

git log 기반으로 릴리즈 노트를 자동 생성한다.

**사용법**
- `/release-notes` — 마지막 태그 → HEAD
- `/release-notes v1.2.0` — 지정 태그 → HEAD
- `/release-notes v1.2.0..v1.3.0` — 태그 범위 지정

---

## Step 1. PowerShell로 커밋 수집

**Claude가 커밋을 분류하지 않는다. PowerShell이 타입별로 그룹핑까지 처리한다.**

```powershell
# 범위 결정: 인자가 없으면 마지막 태그 → HEAD
$range = if ($args[0]) { $args[0] } else {
    $lastTag = git describe --tags --abbrev=0 2>$null
    if ($lastTag) { "$lastTag..HEAD" } else { "HEAD" }
}

Write-Host "RANGE:$range"

$types = @{
    feat     = @()
    fix      = @()
    refactor = @()
    perf     = @()
    chore    = @()
    docs     = @()
    breaking = @()
    other    = @()
}

git log $range --pretty=format:"%H|%s" | ForEach-Object {
    $parts = $_ -split '\|', 2
    $hash  = $parts[0].Substring(0,7)
    $msg   = $parts[1]

    # BREAKING CHANGE 먼저 판별
    if ($msg -match '!:' -or $msg -match 'BREAKING') {
        $types['breaking'] += "[$hash] $msg"
        return
    }

    $matched = $false
    foreach ($t in @('feat','fix','perf','refactor','chore','docs')) {
        if ($msg -match "^${t}[\(\!]?") {
            # 스코프 제거하여 본문만 추출
            $body = $msg -replace "^${t}(\([^)]+\))?:\s*", ''
            $types[$t] += "[$hash] $body"
            $matched = $true
            break
        }
    }
    if (-not $matched) { $types['other'] += "[$hash] $msg" }
}

foreach ($t in @('breaking','feat','fix','perf','refactor','chore','docs','other')) {
    if ($types[$t].Count -gt 0) {
        Write-Host "TYPE:$t"
        $types[$t] | ForEach-Object { Write-Host "  $_" }
    }
}

# 커밋 수 요약
$total = (git log $range --oneline | Measure-Object -Line).Lines
Write-Host "TOTAL:$total"
```

범위 내 태그가 없으면 전체 히스토리가 출력된다. 범위를 명시적으로 지정할 것.

---

## Step 2. 버전 판별

PowerShell 출력의 `TYPE:` 항목을 보고 semver 버전 bump를 결정한다.

| 조건 | bump |
|------|------|
| `breaking` 항목 존재 | **MAJOR** (x.0.0) |
| `feat` 항목 존재 | **MINOR** (x.y.0) |
| `fix` / `perf` 만 존재 | **PATCH** (x.y.z) |
| `refactor` / `chore` / `docs` 만 존재 | **PATCH** 또는 태깅 생략 고려 |

현재 최신 태그를 기준으로 다음 버전을 제안한다.

---

## Step 3. 릴리즈 노트 포매팅

아래 형식으로 출력한다. 항목이 없는 섹션은 생략한다.

```
## v{다음버전} — {YYYY-MM-DD}

> {한 줄 릴리즈 요약 — 이번 릴리즈의 핵심을 한 문장으로}

### 💥 Breaking Changes
- {내용} ({hash})

### ✨ New Features
- {내용} ({hash})

### 🐛 Bug Fixes
- {내용} ({hash})

### ⚡ Performance
- {내용} ({hash})

### ♻️ Refactor
- {내용} ({hash})

### 🔧 Chore / Docs
- {내용} ({hash})

---
Full diff: `git log {range} --oneline`
```

**포매팅 규칙**
- 커밋 메시지에서 스코프(`(server-c)` 등)는 **[serverC]** 형태로 앞에 붙인다.
- 동일한 의미의 커밋이 연속되면 하나로 묶고 "(외 N건)"을 붙인다.
- `chore`/`docs` 는 팀 내부 작업이므로 한 섹션으로 합친다.

---

## Step 4. CHANGELOG.md 업데이트 여부 확인

출력 후 다음 문구로 마무리한다:

```
---
CHANGELOG.md 상단에 추가할까요? (y/n)
```

사용자가 승인하면 `docs/CHANGELOG.md` 파일 상단에 해당 버전 블록을 삽입한다.
파일이 없으면 새로 생성한다.
