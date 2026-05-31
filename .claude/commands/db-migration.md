# /db-migration — DB 마이그레이션 안전성 검토

PR 머지 전 엔티티 변경을 SQL로 제안하고, 기존 마이그레이션 파일의 위험도를 분석한다.

**사용법**
- `/db-migration` — 엔티티 변경 감지 → SQL 제안 → 기존 마이그레이션 파일 위험도 검토
- `/db-migration all` — 전체 마이그레이션 파일 분석 (SQL 제안 생략)

---

## Step 0. 엔티티 변경 감지 및 SQL 초안 제안

**마이그레이션 파일이 없거나 `all` 모드가 아닐 때 실행한다.**

### Step 0-1. PowerShell로 변경된 엔티티 파일 추출

```powershell
$entityDiff = git diff main...HEAD -- "*/entity/*.java" "*/domain/*.java" 2>$null
if (-not $entityDiff) {
    $entityDiff = git diff HEAD~1 HEAD -- "*/entity/*.java" "*/domain/*.java"
}

if (-not $entityDiff) {
    Write-Host "NO_ENTITY_CHANGES"
} else {
    # 변경된 파일 목록
    $changedEntities = git diff --name-only main...HEAD -- "*/entity/*.java" "*/domain/*.java" 2>$null
    Write-Host "CHANGED_ENTITIES:$($changedEntities -join ',')"
    Write-Host "DIFF_START"
    Write-Host $entityDiff
    Write-Host "DIFF_END"
}

# 현재 가장 높은 Flyway 버전 번호 파악
$lastV = Get-ChildItem -Recurse -Filter "V*.sql" | Where-Object { $_.FullName -match "db.migration" } |
    ForEach-Object { if ($_.Name -match "V(\d+)") { [int]$matches[1] } } |
    Sort-Object -Descending | Select-Object -First 1
$nextV = if ($lastV) { $lastV + 1 } else { 1 }
Write-Host "NEXT_VERSION:$nextV"
```

### Step 0-2. Claude가 diff를 읽고 SQL 초안 생성

diff 출력을 분석해 아래 규칙으로 SQL을 제안한다.

| 엔티티 변경 | 생성할 SQL |
|------------|-----------|
| 새 `@Column` 필드 추가 (nullable=true 또는 미지정) | `ALTER TABLE ... ADD COLUMN ... NULL` |
| 새 `@Column(nullable=false)` 필드 추가 | `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT {적절한 기본값}` |
| 필드 타입 변경 | `ALTER TABLE ... MODIFY COLUMN ...` + ⚠️ HIGH 경고 |
| 필드 제거 | `ALTER TABLE ... DROP COLUMN ...` + ⚠️ HIGH 경고 |
| 새 `@Entity` 클래스 | `CREATE TABLE ...` 전체 생성 |
| `@Index` 추가 | `CREATE INDEX ... ALGORITHM=INPLACE, LOCK=NONE` |
| `@UniqueConstraint` 추가 | `ALTER TABLE ... ADD UNIQUE INDEX ...` |
| `@ManyToOne` / `@JoinColumn` 추가 | FK 컬럼 + `ADD CONSTRAINT ... FOREIGN KEY ...` |

**제안 출력 형식:**

```
## 엔티티 변경 감지 → SQL 초안

변경 파일: Goods.java, Payment.java

### 제안 파일명: V{nextV}__{snake_case_설명}.sql

```sql
-- Goods.java: stock 필드 추가
ALTER TABLE goods ADD COLUMN stock INT NOT NULL DEFAULT 0;

-- Payment.java: cancelled_at 필드 추가
ALTER TABLE payment ADD COLUMN cancelled_at DATETIME NULL;
```

⚠️ 검토 필요:
- `stock NOT NULL DEFAULT 0` — 기존 데이터에 0이 적절한지 확인
- 파일을 `{모듈}/src/main/resources/db/migration/` 에 저장하세요

파일을 직접 생성할까요? (y/n)
```

사용자가 y이면 해당 경로에 SQL 파일을 생성한다.
그 후 Step 1(위험도 검토)을 이어서 실행한다.

---

## Step 1. PowerShell로 마이그레이션 파일 수집

```powershell
$mode = $args[0]

# 마이그레이션 파일 경로 패턴
$migPattern = "**/db/migration/V*.sql"

if ($mode -eq "all") {
    $files = Get-ChildItem -Recurse -Filter "V*.sql" | Where-Object { $_.FullName -match "db.migration" }
} else {
    # main 대비 변경된 파일만
    $changed = git diff --name-only main...HEAD 2>$null
    if (-not $changed) { $changed = git diff --name-only HEAD~1 HEAD }
    $files = $changed | Where-Object { $_ -match "db/migration.*V.*\.sql" } | ForEach-Object {
        if (Test-Path $_) { Get-Item $_ }
    }
}

if (-not $files) {
    Write-Host "NO_MIGRATION_FILES"
    exit
}

foreach ($f in $files) {
    Write-Host "FILE:$($f.Name)"
    Get-Content $f.FullName | ForEach-Object { Write-Host "  $_" }
    Write-Host "END_FILE"
}
```

파일이 없으면: `NO_MIGRATION_FILES` 출력 후 종료.

---

## Step 2. 위험 패턴 분석

각 파일의 SQL을 아래 기준으로 분석한다. **Claude가 직접 SQL을 읽고 판별한다.**

### 위험도 기준표

| 등급 | 패턴 | 이유 |
|------|------|------|
| 🔴 HIGH | `DROP TABLE`, `DROP COLUMN` | 데이터 영구 삭제, 롤백 불가 |
| 🔴 HIGH | `ADD COLUMN ... NOT NULL` (DEFAULT 없음) | 대용량 테이블 전체 잠금 |
| 🔴 HIGH | `MODIFY COLUMN`, `CHANGE COLUMN` | 타입 변경 시 묵시적 테이블 리빌드 |
| 🔴 HIGH | `RENAME TABLE`, `RENAME COLUMN` | 실행 중인 앱 쿼리 즉시 깨짐 |
| 🔴 HIGH | `TRUNCATE` | 트랜잭션 롤백 불가 |
| 🔴 HIGH | `UPDATE`/`DELETE` (WHERE 없음) | 전체 행 영향 |
| 🟡 MEDIUM | `CREATE INDEX` (ALGORITHM=INPLACE 없음) | 잠금 발생 가능 |
| 🟡 MEDIUM | `ALTER TABLE` (복합 변경) | 변경 조합에 따라 리빌드 발생 |
| 🟡 MEDIUM | `ADD COLUMN ... NOT NULL DEFAULT` | 대용량 시 메타데이터 잠금 주의 |
| 🟡 MEDIUM | `IF NOT EXISTS` / `IF EXISTS` 미사용 | 재실행 시 오류 |
| 🟢 LOW | `CREATE TABLE`, `ADD COLUMN` (nullable) | 일반적으로 안전 |
| 🟢 LOW | `CREATE INDEX` (ALGORITHM=INPLACE, LOCK=NONE) | 온라인 DDL, 안전 |

---

## Step 3. 출력 형식

파일별로 아래 형식으로 출력한다.

```
### {파일명}

| 구문 | 등급 | 위험 이유 |
|------|------|----------|
| ALTER TABLE goods ADD COLUMN ... NOT NULL | 🔴 HIGH | DEFAULT 없는 NOT NULL 추가 — 테이블 전체 잠금 |
| CREATE INDEX idx_... | 🟡 MEDIUM | ALGORITHM=INPLACE 미지정 |

**종합 판정: 🔴 HIGH / 🟡 MEDIUM / 🟢 LOW**

💡 권장 수정:
- `ADD COLUMN stock INT NOT NULL DEFAULT 0` → DEFAULT 추가
- `CREATE INDEX ... ALGORITHM=INPLACE, LOCK=NONE` → 온라인 DDL 명시
```

HIGH 항목이 하나라도 있으면 종합 판정은 HIGH.

---

## Step 4. 체크리스트 출력

분석 완료 후 아래를 항상 출력한다.

```
### 머지 전 체크리스트
- [ ] 스테이징 환경에서 마이그레이션 실행 검증 완료
- [ ] 롤백 스크립트(Undo) 준비 또는 롤백 계획 수립
- [ ] 대용량 테이블(100만 행↑) 여부 확인
- [ ] 배포 순서 확인: 앱 배포 전 마이그레이션? 후 마이그레이션?
- [ ] 다운타임 필요 여부 팀 공유 완료
```
