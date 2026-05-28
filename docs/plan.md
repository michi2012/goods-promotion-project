# 계획서: serverC MySQL 분리 (DB-per-Service)

- 작성일: 2026-05-28

## 목표
serverA와 공유하던 MySQL(3307)에서 serverC를 분리해, serverC 전용 MySQL 컨테이너(3308)와
`payment` DB를 추가한다. Debezium CDC connector도 serverC 전용으로 분리 등록한다.

## 성공 기준
- [ ] `mysql-c` 컨테이너가 3308포트로 정상 기동됨
- [ ] `server-c`가 `mysql-c:3306/payment`에 연결됨 (기존 `mysql:3306/promotion` 미사용)
- [ ] `payment-outbox-connector`가 Kafka Connect에 정상 등록됨
- [ ] serverA는 기존 `mysql`(3307)을 그대로 사용 — 영향 없음
- [ ] `docker-compose up` 후 모든 서비스 정상 기동

## 비범위 (Out of Scope)
- serverA MySQL 설정 변경
- 기존 데이터 마이그레이션 (개발 환경 — 컨테이너 재시작으로 스키마 재생성)
- 결제 조회 API 추가 — 별도 작업

## 단계별 작업 계획

### 단계 1: docker-compose.yml — mysql-c 서비스 추가
- 변경 파일: `docker-compose.yml`
- 변경 내용: `mysql-c` 서비스 추가 (port 3308:3306, DB `payment`, server-id=2, 바이너리 로그 활성화)
- 검증 방법: YAML 문법 오류 없음 확인
- 롤백 방법: 추가한 블록 제거
- 예상 소요: 짧음

### 단계 2: docker-compose.yml — server-c 의존성 및 환경변수 수정
- 변경 파일: `docker-compose.yml`
- 변경 내용:
  - `server-c.depends_on`: `mysql` → `mysql-c`
  - `SPRING_DATASOURCE_URL`: `mysql:3306/promotion` → `mysql-c:3306/payment`
- 검증 방법: server-c 블록에 `mysql` 참조 잔존 여부 확인
- 롤백 방법: 원복
- 예상 소요: 짧음

### 단계 3: docker-compose.yml — kafka-connect + debezium-init 수정
- 변경 파일: `docker-compose.yml`
- 변경 내용:
  - `kafka-connect.depends_on`에 `mysql-c: condition: service_healthy` 추가
  - `debezium-init.depends_on`에 `mysql-c: condition: service_healthy` 추가
  - `debezium-init.entrypoint`: curl 1개 → 2개 순차 실행 (outbox-connector + payment-outbox-connector)
- 검증 방법: entrypoint 스크립트 문법 확인
- 롤백 방법: 원복
- 예상 소요: 짧음

### 단계 4: debezium/payment-outbox-connector.json 신규 생성
- 변경 파일: `debezium/payment-outbox-connector.json` (신규)
- 변경 내용:
  - connector명: `payment-outbox-connector`
  - `database.hostname`: `mysql-c`
  - `database.server.id`: `184055` (기존 184054와 충돌 방지)
  - `database.include.list`: `payment`
  - `table.include.list`: `payment.outbox_event`
  - `schema.history.internal.kafka.topic`: `schema-changes.payment`
- 검증 방법: JSON 문법 오류 없음 확인
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

### 단계 5: serverC application.yaml 로컬 기본값 수정
- 변경 파일: `serverC/src/main/resources/application.yaml`
- 변경 내용: datasource 기본값 `localhost:3306/promotion` → `localhost:3308/payment`
- 검증 방법: 파일 내 `3306/promotion` 잔존 여부 확인
- 롤백 방법: 원복
- 예상 소요: 짧음

### 단계 6: 문서 업데이트 (arch-snapshot, infra-diagram)
- 변경 파일: `docs/arch-snapshot.md`, `docs/infra-diagram.md`
- 변경 내용: serverC DB를 별도 MySQL(3308/payment)로 반영
- 검증 방법: 문서 내 serverC DB 설명 확인
- 롤백 방법: 원복
- 예상 소요: 짧음

## 리스크 및 대응
- `database.server.id` 충돌: Debezium MySQL connector는 서버별 고유 ID 필요 → 184055로 분리
- 기존 `final_order` 테이블 잔존: `docker-compose down -v` 후 재시작 시 스키마 재생성
- debezium-init curl 2번 실행 중 첫 번째 성공, 두 번째 실패 시: `restart: on-failure`로 재시도

## 의존성
- `mysql-c` healthcheck → `debezium-init` 순서 보장 필요
- 기존 `promotion-outbox-connector` (serverA용)는 변경 없음
