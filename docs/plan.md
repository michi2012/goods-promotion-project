# 계획서: PG 대사 PR③ — 시드 생성기 + 기준선 측정 (BL-047)

## [진행 중] BL-002 PR③ — BL-047

- 작성일: 2026-11-17
- 관련 항목: BL-047 (상위 BL-002, 마지막 PR) / 명세 `docs/specs/pg-reconciliation-batch.md` 구현 단계 ③

## 목표
대사일 하루치 대량 시드(`payments` 행 + PG CSV)를 재현 가능하게 만드는 생성기를 추가하고, 로컬 docker compose에서 `pgReconciliationJob`의 기준선(소요 시간·처리량·Step별 시간·재시작 절약 시간)을 측정해 기록한 뒤, 그 수치로 BL-008 착수 여부를 판단한다.

## 성공 기준
- [ ] 시드 생성기가 같은 인자(대사일·건수·seed)로 두 번 실행하면 바이트 단위로 같은 SQL·CSV를 만든다 (파일 해시 비교)
- [ ] 10만·100만 두 규모에서 대사 결과의 유형별 불일치 건수가 생성기가 심은 기대 건수(생성기가 출력하는 요약)와 정확히 같다 (`reconciliation_mismatches` GROUP BY 조회)
- [ ] 두 규모 각각 3회 이상 반복 측정해 Job 소요 시간·초당 처리 이벤트·Step별 시간의 중앙값(최소~최대)을 `docs/perf/2026-11-17-pg-reconciliation-baseline.md`에 기록 (`BATCH_JOB_EXECUTION`/`BATCH_STEP_EXECUTION` 조회)
- [ ] 100만 규모에서 Step 4(ours→PG) 중간에 serverC를 kill → 재기동 → API 재시작(mode=RESTART)으로 남은 시간·재처리 없음(read count)·최종 불일치 건수 동일을 기록
- [ ] BL-008 판단을 결과 문서에 근거와 함께 적고 백로그에 반영, BL-002 완료·포트폴리오 처리량·arch-snapshot 반영

## 비범위 (Out of Scope)
- 배치 실행 중 실시간 API 간섭(p95) 측정 — 사용자 결정, BL-008 판단 시 필요하면 별도 측정
- 레거시 스케줄러(한 트랜잭션·OFFSET·건별) vs Batch, 키셋 vs OFFSET 속도 비교 — 측정용 코드가 필요해 다음 항목(새 BL)으로 (2026-11-17 사용자 합의)
- 멀티스레드·파티셔닝 비교 — 로컬 serverC CPU 1개라 의미 있는 비교 불가 (기록만)
- chunk 크기·커넥션 풀 튜닝 등 성능 개선 — BL-008
- 프로덕션 코드 변경 (측정 중 버그 발견 시 6-2에 따라 정지·보고)
- 클라우드(EC2/EKS) 측정

## 단계별 작업 계획

### 단계 1: 시드 생성기
- 변경 파일: `perf/seed/pg-reconciliation-seed.mjs`(신규, 의존성 없는 Node), `perf/seed/README.md`(신규, 사용법·적용·정리 명령)
- 변경 내용: 인자 `--date --events --seed --out`. 고정 seed의 PRNG로 결제 행을 만들고 `payments` 다중 행 INSERT SQL(1,000행 단위)과 `pg-transactions-YYYYMMDD.csv`를 쓴다. 구성: 정상 승인 대부분, 승인+취소 약 5%, 자정 경계(PG 23:59:5x / 우리 D+1 00:00:0x — D+1 쪽은 D 대사 대상 아님, 우리가 D 23:59:5x이고 PG도 D인 정상 경계 포함), 불일치 4유형 각 0.1%. `order_id`는 `seed-YYYYMMDD-n`으로 다른 데이터와 구분. 끝에 기대 불일치 건수 요약을 출력한다.
- 검증: 1만 건 소규모로 생성 → 같은 인자 2회 해시 동일 → mysql-c 적용 → 관리자 API로 대사 → 유형별 건수가 요약과 일치
- 롤백: 신규 파일 삭제, `DELETE FROM payments WHERE order_id LIKE 'seed-%'`
- 예상 소요: 보통

### 단계 2: 10만 규모 측정
- 변경 파일: 없음 (측정)
- 변경 내용: 시드 적용 → 워밍업 1회 → 같은 날짜로 3회 실행(COMPLETED 후 재실행 = 새 실행·교체). 매회 정확성(유형별 건수) 확인, 메타 테이블에서 Job·Step 시간과 read/write 건수 수집.
- 검증: 3회 모두 COMPLETED + 건수 일치
- 롤백: 시드 행 삭제
- 예상 소요: 짧음

### 단계 3: 100만 규모 측정 + 재시작 절약 시간
- 변경 파일: 없음 (측정)
- 변경 내용: 단계 2와 같은 절차로 3회. 이어서 1회는 Step 4 진행 중(`BATCH_STEP_EXECUTION.READ_COUNT`가 절반쯤일 때) `docker kill promotion-server-c` → `docker start` → 기동 시 정리로 FAILED 확인 → API 재시작(RESTART) → 재시작 후 소요 시간, Step 4 read count가 남은 분량인지, 최종 건수 동일 여부 기록.
- 검증: RESTART 실행 COMPLETED + 건수 일치
- 롤백: 시드 행 삭제, serverC 재기동
- 예상 소요: 보통

### 단계 3-1: 설정 비교 — 일괄 쓰기·chunk 크기 (2026-11-17 사용자 승인으로 추가)
- 변경 파일: 없음 (측정. serverC 환경변수만 임시 override, 끝나면 원복)
- 변경 내용: 10만 규모(2026-10-02)로 설정 하나씩 바꿔 3회씩: ① `rewriteBatchedStatements=false`(일괄 쓰기 끔) ② chunk 100 ③ chunk 2000. 기준(500, 켬)은 단계 2 값을 같은 날 측정값으로 사용. 컨테이너 재생성 시 `/app/pg-files`가 사라지므로 CSV 다시 복사.
- 검증: 매회 COMPLETED + 건수 일치
- 롤백: override 없이 serverC 재생성
- 예상 소요: 보통

### 단계 4: 결과 기록 + BL-008 판단
- 변경 파일: `docs/perf/2026-11-17-pg-reconciliation-baseline.md`(신규), `.claude/skills/perf-compare/project.md`(배치 지표 조회 쿼리 확정)
- 변경 내용: perf-compare 기록 형식(전후 대신 규모별 기준선). 판단 기준: 100만(일 거래 10배 여유)이 03:00 실행 후 업무 시작 전까지 넉넉히 끝나는지, Step별 병목이 한쪽에 몰리는지. 결론을 BL-008 조건 칸에 반영.
- 검증: 리뷰
- 예상 소요: 짧음

### 단계 5: 마무리 문서
- 변경 파일: `docs/portfolio.md`(처리량 칸), `docs/architecture/arch-snapshot.md`(serverC 배치·대사 테이블·관리자 API 발췌 반영), 명세 완료 기준 체크, `docs/backlog.md`
- 검증: 리뷰
- 예상 소요: 짧음

## 리스크 및 대응
- 100만 건 SQL 파일(약 250MB) 적용이 느리거나 `max_allowed_packet` 초과 → 1,000행 단위 INSERT, 파일을 나눠 적용. 적용 시간은 측정 대상 아님
- 기존 로컬 데이터와 충돌 → 데이터가 없는 과거 대사일 사용 + `seed-` 접두사, 측정 후 시드 행 삭제(사용자 확인 후)
- 같은 머신 다른 컨테이너 부하로 편차 → 측정 중 다른 부하 없음 확인, 반복 간 편차를 함께 기록
- serverC kill 시 다른 진행 중 작업 영향 → 로컬 전용, 측정 시점에 다른 부하 없음
- 측정 중 버그(건수 불일치 등) 발견 → 정지 후 보고 (프로덕션 코드 변경은 계획 밖)

## 의존성
- docker compose의 serverC(`promotion-server-c`, WORKDIR `/app`, 파일 경로 `/app/pg-files`) + mysql-c — 현재 기동 중
- Node (의존성 없음), 새 Gradle 의존성 없음
