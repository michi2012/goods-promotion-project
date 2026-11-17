# 시드 생성기

## PG 대사 배치 — `pg-reconciliation-seed.mjs`

대사일 하루치 serverC `payments` INSERT SQL + PG 거래 파일(CSV)을 만든다. 같은 인자면 같은 파일 (고정 seed). 구성·비율은 스크립트 상단 주석. 실행 끝에 기대 불일치 건수(JSON)를 출력한다.

로컬 docker compose 기준 (Git Bash. 컨테이너 경로 변환을 막으려고 `MSYS_NO_PATHCONV=1`):

```bash
export MSYS_NO_PATHCONV=1
node perf/seed/pg-reconciliation-seed.mjs --date 2026-10-01 --payments 100000 --out perf/seed/out

# 적용: CSV → serverC 파일 디렉터리, SQL → mysql-c (재생성한 컨테이너엔 디렉터리가 없다)
docker exec promotion-server-c mkdir -p /app/pg-files
docker cp perf/seed/out/pg-transactions-20261001.csv promotion-server-c:/app/pg-files/
docker cp perf/seed/out/payments-seed-20261001.sql promotion-mysql-c:/tmp/seed.sql
docker exec promotion-mysql-c sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" payment < /tmp/seed.sql'

# 대사 실행 (같은 날짜 재실행 = 이전 결과 교체)
curl -X POST "http://localhost:8082/api/v1/payments/admin/reconciliations?date=2026-10-01" -H "X-User-Role: ROLE_ADMIN"

# 정리
docker exec promotion-mysql-c sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" payment -e "DELETE FROM payments WHERE order_id LIKE \"seed-%\"; DELETE FROM reconciliation_mismatches WHERE order_id LIKE \"seed-%\"; DELETE FROM pg_settlement_lines WHERE order_id LIKE \"seed-%\""'
docker exec promotion-server-c sh -c 'rm /app/pg-files/pg-transactions-20261001.csv'
```

- 데이터가 없는 과거 날짜를 쓴다. `order_id`는 `seed-YYYYMMDD-n`.
- 결과 확인 쿼리와 측정 기록: `docs/perf/2026-11-17-pg-reconciliation-baseline.md`.
- 산출물(`out/`)은 커밋하지 않는다 (100만 건이면 SQL 약 250MB).
