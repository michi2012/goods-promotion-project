# 체크리스트: serverC MySQL 분리 (DB-per-Service)

- 마지막 업데이트: 2026-05-28

## 진행 상황
- [x] 단계 1: docker-compose.yml — mysql-c 서비스 추가
  - [x] YAML 문법 오류 없음
- [x] 단계 2: docker-compose.yml — server-c depends_on + DATASOURCE_URL 수정
  - [x] server-c 블록에 `mysql` 참조 없음 (`mysql-c`로 교체됨)
- [x] 단계 3: docker-compose.yml — kafka-connect + debezium-init 수정
  - [x] debezium-init entrypoint curl 2개 확인
  - [x] mysql-c depends_on 추가 확인
- [x] 단계 4: debezium/payment-outbox-connector.json 신규 생성
  - [x] JSON 문법 오류 없음
  - [x] database.server.id 고유값(184055) 확인
- [x] 단계 5: serverC application.yaml 로컬 기본값 수정
  - [x] `3306/promotion` 잔존 없음 → `3308/payment`
- [x] 단계 6: 문서 업데이트 (arch-snapshot, infra-diagram)

## 최종 검증
- [x] `docker compose config --quiet` 문법 오류 없음
- [x] serverA 관련 설정 미변경 확인 (mysql:3306/promotion은 server-a에만 존재)
- [x] debezium server.id 충돌 없음 (184054 vs 184055)

## 발견 사항
- (없음)
