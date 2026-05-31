# /coverage — JaCoCo 커버리지 분석

리포트는 사용자가 미리 생성해둔 상태여야 한다.
생성 커맨드: `.\gradlew.bat test jacocoTestReport -x :mcp:test` (모듈 교체)

---

## Step 1. PowerShell로 분석 실행

**Claude가 계산하지 않는다. PowerShell이 필터링·퍼센트·등급까지 모두 처리한다.**

아래 스크립트를 그대로 실행한다. 분석할 모듈만 `$modules` 배열에서 조정한다.

```powershell
$modules = @("serverA","serverB","serverC","mcp")
foreach ($mod in $modules) {
    $path = "$mod/build/reports/jacoco/test/jacocoTestReport.xml"
    if (-not (Test-Path $path)) { Write-Host "[$mod] NO_REPORT"; continue }
    [xml]$xml = Get-Content $path -Encoding UTF8
    $tlnM=0; $tlnC=0; $tbrM=0; $tbrC=0; $tmtM=0; $tmtC=0
    $danger=@(); $warn=@()
    $xml.report.package.class | ForEach-Object {
        $name = $_.name.Split('/')[-1]
        $ln = $_.counter | Where-Object { $_.type -eq 'LINE' }
        $br = $_.counter | Where-Object { $_.type -eq 'BRANCH' }
        $mt = $_.counter | Where-Object { $_.type -eq 'METHOD' }
        $lnM=[int]($ln.missed+0); $lnC=[int]($ln.covered+0)
        $brM=[int]($br.missed+0); $brC=[int]($br.covered+0)
        $mtM=[int]($mt.missed+0); $mtC=[int]($mt.covered+0)
        if (($lnM+$lnC) -eq 0) { return }
        $tlnM+=$lnM; $tlnC+=$lnC; $tbrM+=$brM; $tbrC+=$brC; $tmtM+=$mtM; $tmtC+=$mtC
        $lp = [math]::Round($lnC/($lnM+$lnC)*100)
        $bp = if(($brM+$brC)-gt 0){[math]::Round($brC/($brM+$brC)*100)}else{-1}
        $isDanger = $lp -lt 40 -or ($bp -ge 0 -and $bp -lt 30)
        $isWarn   = -not $isDanger -and ($lp -lt 70 -or ($bp -ge 0 -and $bp -lt 60))
        $bstr = if($bp -ge 0){"$bp%"}else{"N/A"}
        if ($isDanger) { $danger += "${name}|${lp}%|${bstr}" }
        elseif ($isWarn) { $warn += "${name}|${lp}%|${bstr}" }
    }
    $lt=$tlnM+$tlnC; $bt=$tbrM+$tbrC; $mtt=$tmtM+$tmtC
    $lr=if($lt-gt 0){[math]::Round($tlnC/$lt*100)}else{0}
    $brr=if($bt-gt 0){[math]::Round($tbrC/$bt*100)}else{0}
    $mr=if($mtt-gt 0){[math]::Round($tmtC/$mtt*100)}else{0}
    Write-Host "==$mod=="
    Write-Host "LINE:$lr%($tlnC/$lt) BRANCH:$brr%($tbrC/$bt) METHOD:$mr%($tmtC/$mtt)"
    if($danger.Count -gt 0){Write-Host "DANGER:$($danger -join '|')"}
    if($warn.Count -gt 0){Write-Host "WARN:$($warn -join '|')"}
}
```

리포트가 없으면:
> `.\gradlew.bat :{module}:test :{module}:jacocoTestReport`

---

## Step 2. 출력 결과 포매팅

PowerShell 출력을 받아 아래 형식으로 변환한다. 계산은 이미 완료된 상태이므로 숫자를 그대로 옮기면 된다.

### 등급 기준

| 등급 | 라인 | 브랜치 |
|------|------|--------|
| 🔴 위험 | < 40% | < 30% |
| 🟡 경고 | 40–70% | 30–60% |
| 🟢 양호 | ≥ 70% | ≥ 60% |

### 출력 형식

```
## {모듈} 커버리지 (보일러플레이트 제외)
라인 XX% | 브랜치 XX% | 메서드 XX%

🔴 위험: 클래스명(라인/브랜치), ...
🟡 경고: 클래스명(라인/브랜치), ...
```

위험·경고 클래스가 없으면 🟢 양호로 표시한다.

---

## Step 3. 보완 제안

위험 클래스를 우선순위 순으로 나열한다:
1. Service, Entity (도메인 핵심)
2. Kafka Consumer, Repository
3. Controller
4. 나머지

```
### 보완 권장 순서
🔴 1. `클래스명` — 미커버 이유 한 줄
🟡 2. `클래스명` — ...

테스트를 작성할까요?
```

---

## 참고: 리포트 생성 커맨드

```powershell
# 단일 모듈
.\gradlew.bat :serverA:test :serverA:jacocoTestReport

# 전체 (mcp 제외)
.\gradlew.bat :serverA:test :serverA:jacocoTestReport :serverB:test :serverB:jacocoTestReport :serverC:test :serverC:jacocoTestReport
```
