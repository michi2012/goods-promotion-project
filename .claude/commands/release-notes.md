# /release-notes — 릴리즈 노트 생성

git log 기반으로 릴리즈 노트를 자동 생성한다.

**사용법**
- `/release-notes` — 마지막 태그 → HEAD (태그 없으면 CHANGELOG.md 기준 자동 결정)
- `/release-notes v1.2.0` — 지정 태그 → HEAD
- `/release-notes v1.2.0..v1.3.0` — 태그 범위 지정

---

## Step 1. 범위 결정 및 커밋 수집

**Claude가 커밋을 분류하지 않는다. PowerShell이 타입별로 그룹핑까지 처리한다.**

```powershell
# 1-A. 범위 결정
$argRange = $args[0]  # 사용자가 인자로 넘긴 범위 (없으면 $null)

$lastTag = git describe --tags --abbrev=0 2>$null
$lastVersion = $null   # CHANGELOG에서 읽은 버전
$rangeBase   = $null   # 커밋 범위의 시작점

if ($argRange) {
    $range = $argRange
    Write-Host "RANGE:$range"
} elseif ($lastTag) {
    $range = "$lastTag..HEAD"
    $lastVersion = $lastTag -replace '^v', ''
    Write-Host "RANGE:$range"
} else {
    # 태그 없음 → CHANGELOG.md에서 마지막 버전 탐색
    $changelogPath = "docs/CHANGELOG.md"
    if (Test-Path $changelogPath) {
        $match = Select-String -Path $changelogPath -Pattern '^## v(\d+\.\d+\.\d+)' | Select-Object -First 1
        if ($match) {
            $lastVersion = $match.Matches[0].Groups[1].Value
            Write-Host "CHANGELOG_VERSION:v$lastVersion"
            # CHANGELOG를 마지막으로 수정한 커밋 = 이전 릴리즈 기준점
            $rangeBase = git log --oneline -1 --pretty=format:"%H" -- $changelogPath
            if ($rangeBase) {
                $range = "$rangeBase..HEAD"
                Write-Host "RANGE:$range (CHANGELOG 기준 — 이전 릴리즈 커밋 이후)"
            } else {
                $range = "HEAD"
                Write-Host "RANGE:HEAD (전체 히스토리)"
            }
        } else {
            $range = "HEAD"
            Write-Host "RANGE:HEAD (전체 히스토리 — 최초 릴리즈)"
        }
    } else {
        $range = "HEAD"
        Write-Host "RANGE:HEAD (전체 히스토리 — 최초 릴리즈)"
    }
}

# 1-B. 타입별 분류
$types = @{
    feat     = [System.Collections.Generic.List[string]]::new()
    fix      = [System.Collections.Generic.List[string]]::new()
    refactor = [System.Collections.Generic.List[string]]::new()
    perf     = [System.Collections.Generic.List[string]]::new()
    chore    = [System.Collections.Generic.List[string]]::new()
    docs     = [System.Collections.Generic.List[string]]::new()
    breaking = [System.Collections.Generic.List[string]]::new()
    other    = [System.Collections.Generic.List[string]]::new()
}

git log $range --pretty=format:"%H|%s" | ForEach-Object {
    $parts = $_ -split '\|', 2
    $hash  = $parts[0].Substring(0,7)
    $msg   = $parts[1]

    if ($msg -match '!:' -or $msg -match 'BREAKING') {
        $types['breaking'].Add("[$hash] $msg")
        return
    }

    $matched = $false
    foreach ($t in @('feat','fix','perf','refactor','chore','docs')) {
        if ($msg -match "^${t}[\(\!]?") {
            $body = $msg -replace "^${t}(\([^)]+\))?:\s*", ''
            $types[$t].Add("[$hash] $body")
            $matched = $true
            break
        }
    }
    if (-not $matched) { $types['other'].Add("[$hash] $msg") }
}

foreach ($t in @('breaking','feat','fix','perf','refactor','chore','docs','other')) {
    if ($types[$t].Count -gt 0) {
        Write-Host "TYPE:$t"
        $types[$t] | ForEach-Object { Write-Host "  $_" }
    }
}

$total = (git log $range --oneline | Measure-Object -Line).Lines
Write-Host "TOTAL:$total"
Write-Host "LAST_VERSION:$lastVersion"
```

---

## Step 2. 버전 판별

PowerShell 출력의 `LAST_VERSION` 또는 `CHANGELOG_VERSION`을 기준으로 다음 버전을 계산한다.
태그도 CHANGELOG도 없으면 **v1.0.0**을 첫 버전으로 제안한다.

| 조건 | bump |
|------|------|
| `breaking` 항목 존재 | **MAJOR** (x.0.0) |
| `feat` 항목 존재 | **MINOR** (x.y.0) |
| `fix` / `perf` 만 존재 | **PATCH** (x.y.z) |
| `refactor` / `chore` / `docs` 만 존재 | **PATCH** 또는 태깅 생략 고려 |

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

## Step 4. CHANGELOG.md 업데이트 및 git tag 생성

릴리즈 노트 출력 후 아래 순서로 진행한다.

### 4-A. CHANGELOG 업데이트
```
CHANGELOG.md 상단에 추가할까요? (y/n)
```
사용자가 승인하면 `docs/CHANGELOG.md` 파일 상단에 해당 버전 블록을 삽입한다.
파일이 없으면 새로 생성한다.

### 4-B. git tag 생성 (CHANGELOG 업데이트 여부와 무관하게 항상 제안)
```
git tag v{버전} HEAD 로 태그를 생성할까요? (y/n)
[생성하면 다음 /release-notes 실행 시 자동으로 이번 버전 이후 커밋만 수집됩니다]
```
사용자가 승인하면 다음 명령어를 실행한다:
```powershell
git tag v{버전}
Write-Host "태그 v{버전} 생성 완료. 'git push origin v{버전}' 으로 원격에 푸시할 수 있습니다."
```
