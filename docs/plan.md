# 계획서: DB Polling → CDC (Debezium) 전환

- 작성일: 2026-05-24
- 이전 작업: 트랜잭셔널 아웃박스 패턴 도입 (완료)

## 목표

Server A, C의 `OutboxRelayScheduler` (DB 폴링)를 제거하고,
Kafka Connect + Debezium MySQL Connector (CDC)로 `outbox_event` 테이블 INSERT를
자동으로 감지해 Kafka로 발행한다.
downstream consumer(Server B 등) 코드 변경은 없다.

## 성공 기준

- [ ] `docker-compose up` 만으로 Debezium 커넥터가 자동 등록된다
  (검증: `curl http://localhost:8083/connectors` 응답에 `weverse-outbox-connector` 포함)
- [ ] Server A, C 빌드 통과: `.\gradlew.bat build`
- [ ] `OutboxRelayScheduler.java`, `OutboxStatus.java`가 Server A/C 양쪽에서 모두 삭제됨
- [ ] `OutboxEvent` 엔티티에 `status`, `sentAt` 필드가 없음 (코드 확인)
- [ ] `OutboxEventRepository`에 폴링/락/상태업데이트 쿼리가 없음 (코드 확인)
- [ ] Debezium이 `outbox_event` INSERT를 캡처해 올바른 Kafka 토픽으로 라우팅함
  (검증: `curl http://localhost:8083/connectors/weverse-outbox-connector/status` 에서 `RUNNING` 확인)

## 비범위 (Out of Scope)

- Kafka Connect UI (Kafdrop, Confluent Control Center 등) 추가
- Debezium GTID 모드 설정 (binlog 기본 ROW 모드로 충분)
- Server B 코드 변경 (EventRouter SMT가 payload를 그대로 전달하므로 불필요)
- FAILED/DEAD_LETTER 큐 처리
- Debezium 고가용성 (distributed mode 등)

## 단계별 작업 계획

### 단계 1: 인프라 — docker-compose.yml + 커넥터 설정

- 변경 파일:
  - `docker-compose.yml` — MySQL binlog 설정 추가, kafka-connect 서비스 추가, debezium-init 서비스 추가
  - `debezium/outbox-connector.json` (신규) — Debezium MySQL connector + EventRouter SMT 설정
- 변경 내용:
  - MySQL: `--log-bin=mysql-bin --binlog-format=ROW --binlog-row-image=FULL --server-id=1` 추가
  - `kafka-connect`: `debezium/connect:2.7` 이미지, REST API 8083 포트
  - `debezium-init`: `curlimages/curl`, kafka-connect 헬스체크 후 connector POST
- 검증 방법: `docker-compose up kafka-connect` 후 `curl http://localhost:8083/` 응답 확인
- 롤백 방법: docker-compose.yml git 되돌리기, debezium/ 디렉토리 삭제
- 예상 소요: 보통

### 단계 2: Server A Spring 코드 변경

- 삭제 파일:
  - `serverA/.../outbox/OutboxRelayScheduler.java`
  - `serverA/.../outbox/OutboxStatus.java`
  - `serverA/src/test/.../outbox/OutboxRelaySchedulerTest.java`
- 수정 파일:
  - `serverA/.../outbox/OutboxEvent.java` — status, sentAt 제거, index 단순화 (created_at만)
  - `serverA/.../outbox/OutboxEventRepository.java` — 폴링/락/상태쿼리 삭제, deleteOldEvents(created_at 기준)만 남김
  - `serverA/.../outbox/OutboxEventServiceTest.java` — `savedEvent.getStatus()` 검증 제거
  - `serverA/.../config/SchedulingConfig.java` — pool size 4 → 3
- 신규 파일:
  - `serverA/.../outbox/OutboxCleanupScheduler.java` — 1시간마다 24h 초과 레코드 삭제
- 검증 방법: `.\gradlew.bat :serverA:build`
- 롤백 방법: git으로 단계 1 상태로 되돌리기
- 예상 소요: 보통

### 단계 3: Server C Spring 코드 변경

- 삭제 파일:
  - `serverC/.../outbox/OutboxRelayScheduler.java`
  - `serverC/.../outbox/OutboxStatus.java`
  - `serverC/src/test/.../outbox/OutboxRelaySchedulerTest.java`
- 수정 파일:
  - `serverC/.../outbox/OutboxEvent.java` — status, sentAt 제거, index 단순화
  - `serverC/.../outbox/OutboxEventRepository.java` — 동일 단순화
  - `serverC/.../outbox/OutboxEventServiceTest.java` — status 검증 제거
- 신규 파일:
  - `serverC/.../outbox/OutboxCleanupScheduler.java` — 1시간마다 cleanup
- 유지 파일:
  - `serverC/.../config/SchedulingConfig.java` — cleanup 스케줄러 실행에 필요
- 검증 방법: `.\gradlew.bat :serverC:build`
- 롤백 방법: git으로 되돌리기
- 예상 소요: 짧음 (Server A와 동일 패턴)

### 단계 4: 전체 빌드 최종 검증

- 검증 방법: `.\gradlew.bat build`
- 롤백 방법: 없음 (검증 단계)
- 예상 소요: 짧음

## 리스크 및 대응

- **snapshot.mode=schema_only**: 기존 DB의 outbox_event 레코드를 재발행하지 않음. 이미 처리된 이벤트 중복 방지. Docker 재시작 시 DB 초기화되므로 실제 문제 없음.
- **EventRouter SMT와 컬럼명 매핑**: `aggregate_id`(언더스코어) 컬럼을 `table.field.event.key=aggregate_id`로 명시. Debezium이 자동으로 camelCase → snake_case 매핑.
- **DELETE 이벤트 노이즈**: cleanup 스케줄러가 레코드를 삭제할 때 Debezium이 DELETE CDC 이벤트 발생. `table.op.invalid.behavior=skip` + EventRouter 기본 동작(INSERT만 라우팅)으로 무시됨.
- **Kafka Connect 기동 시간**: 30-60초 소요. debezium-init의 restart:on-failure로 재시도 보장.

## 의존성

- `debezium/connect:2.7` 이미지에 MySQL connector 내장 (별도 설치 불필요)
- MySQL 8.0: binlog ROW format 기본 지원
- `weverse_promo.outbox_event` 테이블: Server A, C 공유 (하나의 커넥터로 커버)
- Kafka Connect REST API: 8083 포트
