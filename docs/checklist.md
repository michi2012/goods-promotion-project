# 체크리스트: build.gradle MSA 독립화 + docker-compose 분리

- 마지막 업데이트: 2026-06-04

## 진행 상황

- [x] 단계 1: 루트 build.gradle MSA화
  - [x] `subprojects {}` 블록 제거
  - [x] `plugins {}` → `apply false` 전환
  - [x] 검증: `./gradlew.bat projects` 정상 출력

- [x] 단계 2: 각 서버 build.gradle 독립화
  - [x] serverA/build.gradle — plugins, toolchain, 공통 deps, jacocoTestReport 추가
  - [x] serverB/build.gradle — 동일, testcontainers:mysql 제외
  - [x] serverC/build.gradle — 동일
  - [x] aiops/build.gradle — 동일
  - [x] 검증: `./gradlew.bat build -x test` BUILD SUCCESSFUL

- [x] 단계 3: docker-compose.infra.yml 생성
  - [x] mysql, mysql-c, redis, redis-b, kafka, kafka-connect, debezium-init, redpanda-console 포함
  - [x] 로컬 bridge network (infra-network) 사용
  - [x] 검증: `docker compose -f docker-compose.infra.yml config` 통과

- [x] 단계 4: docker-compose.monitoring.yml 생성
  - [x] otel-collector, prometheus, grafana, alertmanager, tempo, vector, loki, exporters, cadvisor, aiops 포함
  - [x] shared-logs volume 없음
  - [x] 로컬 bridge network (monitoring-network) 사용
  - [x] 검증: `docker compose -f docker-compose.monitoring.yml config` 통과 (env 경고는 정상)

## 최종 검증
- [x] `./gradlew.bat build -x test` 전체 빌드 성공
- [x] 기존 `docker-compose.yml` 변경 없음
- [x] 루트 `build.gradle`에 `subprojects {}` 없음

## 발견 사항
- (작업 중 기록)
