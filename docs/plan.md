# 계획서: 대사 배치 성능 개선 — 건별 조회 → chunk 일괄 조회 + chunk 크기 (BL-008)

## [완료] BL-008 (PR #66)

- 작성일: 2026-11-17
- 관련 항목: BL-008 / 기준선 `docs/perf/2026-11-17-pg-reconciliation-baseline.md` / 명세 `docs/specs/pg-reconciliation-batch.md`

## 목표
대사 Job의 비교 Step 두 개(우리→PG, PG→우리)가 건마다 상대편을 1회씩 조회하던 것을 chunk 단위 `IN` 조회 1회로 바꿔 처리 시간을 줄이고, 그 뒤 chunk 크기를 측정으로 다시 정한다. 판정 결과는 바뀌지 않아야 한다.

## 성공 기준
- [ ] 판정 로직 불변: `ReconciliationMatcherTest`·`ReconciliationJobTest`·재현 테스트 3종(`*ReproTest`)·`ReconciliationMetricsTest` 통과 (`.\gradlew.bat :serverC:test --tests "promotion.serverC.batch.*"`)
- [ ] 새 일괄 조회 메서드 테스트 통과 (`PgSettlementLineRepositoryTest`, `PaymentRepositoryTest`)
- [ ] 100만(2026-10-04, seed 47) 3회: 유형별 불일치 993/988/1004/982 일치 + Job·비교 Step 시간을 기준선(재실행 713.8초, 비교 Step 268.2·267.1초)과 비교해 기록. 쿼리 수 감소를 COMMIT_COUNT·chunk 크기로 설명
- [ ] 10만(2026-10-02) chunk 500/1000/2000/5000 각 3회 → 기본값 결정 근거(시간 + 실패 시 재처리 분량) 기록, `application.yaml` 반영
- [ ] 결과 문서 `docs/perf/2026-11-17-pg-reconciliation-batch-lookup.md` + 포트폴리오 반영 판단

## 비범위 (Out of Scope)
- 재실행 시 대량 DELETE 개선 — 매일 03:00 첫 실행엔 해당 없음, 수동 재실행만. 새 조건부 BL로 분리 (사용자 결정)
- 실시간 간섭(p95) 측정 — 비교 Step은 `payments`를 일반 SELECT로만 읽어 InnoDB 잠금을 걸지 않음. 근거만 기록
- 멀티스레드·파티셔닝, 배치 전용 커넥션 풀 — 현 규모 불필요
- 판정 규칙(Matcher)·테이블·마이그레이션 변경
- BL-052 레거시 비교 — 불필요로 종료 (정확성은 BL-050 재현 테스트, 재시작 이득은 BL-047 실측으로 이미 증명. 2026-11-17 사용자 결정)

## 단계별 작업 계획

### 단계 1: 일괄 조회 메서드
- 변경 파일: `repository/PgSettlementLineRepository.java`(`findAll(reconDate, eventType, orderIds)` → `Map<orderId, PgLine>`), `repository/PaymentRepository.java`(`findEventTimes(orderIds)` → `Map<orderId, PaymentEventTimes>`), 각 테스트
- 변경 내용: `WHERE recon_date = ? AND event_type = ? AND order_id IN (...)` / `WHERE order_id IN (...)`. 빈 목록이면 쿼리 없이 빈 Map. 기존 단건 메서드는 다른 곳에서 쓰지 않으면 제거
- 검증: `--tests "*PgSettlementLineRepositoryTest" --tests "*PaymentRepositoryTest"`
- 롤백: git 되돌리기
- 예상 소요: 짧음

### 단계 2: 비교 Step을 chunk 일괄 판정으로
- 변경 파일: `batch/ReconciliationJobConfig.java`(비교 Step 3개의 processor 제거, writer가 chunk의 키를 모아 일괄 조회 → `ReconciliationMatcher`로 판정 → 불일치만 저장), 필요 시 테스트 보조 코드
- 변경 내용: 판정·저장이 같은 chunk 트랜잭션이라 재시작·중복 없음 보장 유지. Step의 WRITE_COUNT 의미가 "불일치 수" → "처리 건수"로 바뀜(메트릭·리포트는 결과 테이블에서 세므로 영향 없음 — 확인함)
- 검증: `.\gradlew.bat :serverC:test --tests "promotion.serverC.batch.*"` (Testcontainers, docker 기동 중)
- 롤백: git 되돌리기
- 예상 소요: 보통

### 단계 3: 100만 전후 측정
- 변경 파일: 없음 (측정). serverC 이미지 재빌드·재생성(`docker compose build server-c` → `up -d server-c`, 백그라운드)
- 변경 내용: 시드 재생성(같은 인자, 해시 동일) → 적용 → 3회 → 건수·Step 시간 수집. 개선 전은 오늘 기준선 재사용(같은 날·시드·환경)
- 검증: 매회 COMPLETED + 건수 일치
- 롤백: 시드 삭제
- 예상 소요: 보통

### 단계 4: chunk 크기 비교 (10만) + 기본값 반영 — **생략 (2026-11-17 사용자 결정)**: 일괄 조회 후엔 chunk가 `IN` 쿼리 횟수만 좌우해 효과가 작을 것으로 예상, 500으로 100만 약 5분이라 충분. 500 유지
- 변경 파일: `serverC/src/main/resources/application.yaml`(`reconciliation.chunk-size`), 측정은 compose override로
- 변경 내용: 500/1000/2000/5000 각 워밍업 1 + 3회. 기본값은 "충분히 빠르면서 실패 시 재처리 분량이 작은 값"으로 결정
- 검증: 매회 건수 일치, 결정값으로 `batch.*` 테스트 재실행
- 롤백: yaml 되돌리기
- 예상 소요: 보통

### 단계 5: 문서
- 변경 파일: `docs/perf/2026-11-17-pg-reconciliation-batch-lookup.md`(신규), 명세 변경 이력(chunk 크기·일괄 판정), arch-snapshot 대사 절(chunk 값), 백로그(BL-008 완료 준비, DELETE 조건부 새 BL), 포트폴리오(판단 후)
- 검증: 리뷰
- 예상 소요: 짧음

## 리스크 및 대응
- writer에서 판정하면 재시작 시 판정 누락·중복 → 판정과 저장이 같은 chunk 트랜잭션, 유니크 키 중복 무시는 그대로. `ReconciliationRestartReproTest`로 확인
- `IN` 목록이 커져 쿼리가 느림(chunk 5000) → 측정으로 확인, 기본값 선택에 반영
- 경계 판정에 필요한 정보(D-1 꼬리 줄·D 밖 시각)를 일괄 조회가 빠뜨림 → 단건 조회와 같은 WHERE 조건(recon_date 기준, 시각 조건 없음) 유지, `ReconciliationJobTest` 경계 시나리오로 확인
- 위험 변경(대사 정확성) → PR 후 `/pr-review` 필수

## 의존성
- docker compose serverC·mysql-c (기동 중), Testcontainers
- 새 Gradle 의존성·마이그레이션 없음
