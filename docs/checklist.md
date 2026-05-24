# 체크리스트: DB Polling → CDC (Debezium) 전환

- 마지막 업데이트: 2026-05-24

## 진행 상황

- [x] **단계 1**: 인프라 — docker-compose.yml + 커넥터 설정
  - [x] `docker-compose.yml` — MySQL binlog 옵션 4개 추가
  - [x] `docker-compose.yml` — kafka-connect 서비스 (debezium/connect:2.7) 추가
  - [x] `docker-compose.yml` — debezium-init 서비스 (curlimages/curl) 추가
  - [x] `debezium/outbox-connector.json` 생성 (EventRouter SMT 포함)
  - [ ] 검증: `curl http://localhost:8083/` 응답 확인 (Kafka Connect REST API) — docker 기동 시 확인
  - [ ] 검증: `curl http://localhost:8083/connectors/weverse-outbox-connector/status` → RUNNING — docker 기동 시 확인

- [x] **단계 2**: Server A Spring 코드 변경
  - [x] `OutboxRelayScheduler.java` 삭제
  - [x] `OutboxStatus.java` 삭제
  - [x] `OutboxRelaySchedulerTest.java` 삭제
  - [x] `OutboxEvent.java` — status, sentAt 제거, index 단순화 (created_at만)
  - [x] `OutboxEventRepository.java` — 폴링/락/상태 쿼리 삭제, deleteOldEvents 단순화
  - [x] `OutboxCleanupScheduler.java` 신규 생성 (1시간 cleanup)
  - [x] `OutboxEventServiceTest.java` — status 검증 제거
  - [x] `SchedulingConfig.java` — pool size 4 → 3
  - [x] 검증: `.\gradlew.bat :serverA:build` → BUILD SUCCESSFUL

- [x] **단계 3**: Server C Spring 코드 변경
  - [x] `OutboxRelayScheduler.java` 삭제
  - [x] `OutboxStatus.java` 삭제
  - [x] `OutboxRelaySchedulerTest.java` 삭제
  - [x] `OutboxEvent.java` — status, sentAt 제거, index 단순화
  - [x] `OutboxEventRepository.java` — 단순화
  - [x] `OutboxCleanupScheduler.java` 신규 생성
  - [x] `OutboxEventServiceTest.java` — status 검증 제거
  - [x] 검증: `.\gradlew.bat :serverC:build` → BUILD SUCCESSFUL

- [x] **단계 4**: 전체 빌드 최종 검증
  - [x] 검증: `.\gradlew.bat build` → BUILD SUCCESSFUL

## 최종 검증

- [x] `OutboxRelayScheduler.java` Server A/C 모두 없음
- [x] `OutboxStatus.java` Server A/C 모두 없음
- [x] `OutboxEvent` 엔티티에 status, sentAt 필드 없음 (코드 확인)
- [x] `OutboxEventRepository`에 FOR UPDATE SKIP LOCKED 쿼리 없음
- [x] `docker-compose.yml`에 kafka-connect, debezium-init 서비스 추가됨
- [x] plan.md 비범위 침범 없음 (Server B 건드림 없음, Kafka Connect UI 없음)

## 발견 사항 (작업 중 별도 처리 필요한 것)

- (작업 중 기록)
