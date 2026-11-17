# 맥락 노트: 대사 배치 성능 개선 (BL-008)

## [진행 중] BL-008

## 왜 이 방식을 선택했는가
- 기준선(BL-047)에서 비교 Step 두 개가 전체의 약 75%. 원인은 `ItemProcessor`가 건마다 상대편을 1회 조회(100만 기준 약 200만 쿼리). processor는 한 건씩 받으므로 묶을 수 없고, chunk 전체를 받는 writer에서 `IN` 조회 1회로 바꾼다.
- 판정은 기존 `ReconciliationMatcher`를 그대로 호출 — 정확성 근거(재현 테스트·Job 테스트)를 바꾸지 않고 속도만 바꾼다.
- chunk 크기는 일괄 조회 후 다시 측정 — 조회가 묶이면 chunk가 쿼리 횟수까지 좌우해 기존 측정(chunk 2000 −18%)과 결과가 달라진다.
- 개선 전 수치는 오늘 기준선 재사용: 같은 날·같은 시드(seed 47, 결정적)·같은 환경 (perf-compare "같은 날" 조건 충족). 사용자 확인.
- 측정 규모: 일괄 조회 전후는 100만(포트폴리오 수치), chunk 비교는 10만 (사용자 결정).
- 재실행 DELETE 개선은 분리 — 매일 03:00 실행은 첫 실행이라 삭제 대상 없음 (사용자 결정).
- BL-052(레거시 비교)는 불필요로 종료 — Spring Batch는 성능이 아니라 진행 위치·재시작·중복 실행 방지를 위한 선택이고, 그 이득은 재현 테스트·재시작 실측으로 이미 증명 (사용자 결정).

## 검토했으나 채택하지 않은 대안
### chunk 크기만 올리기
- 무엇: 설정만 2000으로 (−18%)
- 왜 안 썼나: 건별 쿼리는 그대로 — 근본 원인이 아님. 일괄 조회 후 2단계 튜닝으로

### processor 안에서 캐시/선조회
- 무엇: `ItemReadListener`·`beforeChunk`로 미리 조회해 processor가 캐시를 봄
- 왜 안 썼나: 읽기와 조회 시점이 갈라져 재시작 시 상태 관리가 늘어남. writer 한 곳에서 조회·판정·저장하는 게 단순

### 실시간 간섭 측정
- 무엇: 배치 중 결제 API p95
- 왜 안 썼나: 비교 Step은 `payments`를 일반 SELECT(잠금 없는 일관된 읽기)로만 읽는다. 쓰기는 대사 테이블에만

## 기존 코드베이스 컨벤션
- 대사 저장소는 JdbcTemplate (`repository/PgSettlementLineRepository.java`, `PaymentRepository.findEventTimes`)
- 저장소 테스트: `@JdbcTest` + `AbstractRepositoryTest`(Testcontainers MySQL) — `test/.../repository/PgSettlementLineRepositoryTest.java`
- Job 테스트: `test/.../batch/ReconciliationJobTest.java` + `ReconciliationJobTestSupport`
- 측정: `perf/seed/README.md`, 결과 `docs/perf/`

## 관련 파일/위치
- `serverC/src/main/java/promotion/serverC/batch/ReconciliationJobConfig.java` — Step·reader·processor·writer
- `serverC/src/main/java/promotion/serverC/batch/ReconciliationMatcher.java` — 판정 (변경 없음)
- `serverC/src/main/java/promotion/serverC/repository/{PgSettlementLineRepository,PaymentRepository}.java` — 조회
- `serverC/src/main/resources/application.yaml` — `reconciliation.chunk-size`

## 외부 참조
- 기준선 `docs/perf/2026-11-17-pg-reconciliation-baseline.md`
