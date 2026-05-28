# 맥락 노트: serverC MySQL 분리 (DB-per-Service)

## 왜 이 방식을 선택했는가
serverA와 serverC가 같은 MySQL 인스턴스(`promotion` DB)를 공유하는 것은 마이크로서비스
DB-per-Service 원칙 위반이다. serverA DB 장애 시 serverC 결제 처리까지 동반 중단되는
단일 장애점(SPOF) 문제도 있다. 개발 환경에서 분리해두면 실제 운영 환경과의 갭을 줄일 수 있다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 같은 MySQL 인스턴스 내 별도 DB (schema 분리만)
- 무엇: `promotion` DB와 `payment` DB를 같은 MySQL 컨테이너에 두기
- 왜 안 썼나: 컨테이너 레벨 격리가 없어 SPOF 문제가 그대로 남음

### 대안 B: debezium-init 컨테이너 2개로 분리
- 무엇: `debezium-init`(serverA용)과 `debezium-init-c`(serverC용) 별도 컨테이너
- 왜 안 썼나: 단순한 curl 2번 추가인데 컨테이너를 늘리는 건 과도함

## 기존 코드베이스 컨벤션
- MySQL 컨테이너: binlog ROW 포맷 필수 (Debezium CDC 요건)
- Debezium server.id: MySQL replication 프로토콜 요건상 인스턴스별 고유값 필요
- 기존 serverA connector server.id: 184054 → 신규 184055

## 관련 파일/위치
- `docker-compose.yml` — mysql-c 추가, server-c/kafka-connect/debezium-init 수정
- `debezium/outbox-connector.json` — serverA용 (변경 없음)
- `debezium/payment-outbox-connector.json` — serverC용 (신규)
- `serverC/src/main/resources/application.yaml` — 로컬 기본 datasource URL 수정
