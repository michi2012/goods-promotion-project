# 계획서: build.gradle MSA 독립화 + docker-compose 분리

- 작성일: 2026-06-04

## 목표
Gradle 멀티 모듈의 공통 의존성을 루트에서 각 서버 build.gradle로 내려 각 서버가 독립 빌드 단위가 되도록 한다.
docker-compose를 infra / monitoring 2파일로 분리해 각각 별도 EC2에 배포 가능하게 한다.
app 서버(A/B/C)는 각 EC2에서 docker run으로 배포한다. 로컬 개발용 통합 docker-compose.yml은 유지한다.

## 성공 기준
- [ ] `./gradlew.bat :serverA:compileJava`, `:serverB:`, `:serverC:`, `:aiops:` 각각 독립 빌드 성공
- [ ] `./gradlew.bat build -x test` 전체 빌드 성공
- [ ] 루트 `build.gradle`에 `subprojects {}` 블록이 없음
- [ ] `docker-compose.infra.yml`, `docker-compose.monitoring.yml` 2파일 생성 완료
- [ ] 기존 `docker-compose.yml` 변경 없음

## 비범위 (Out of Scope)
- 각 서버 Dockerfile 변경 없음
- prometheus.yml scrape target IP 변경 (EC2 IP 확정 후 직접 수정)
- logback 설정 변경 (stdout은 이미 docker가 캡처함)
- CI/CD 파이프라인 설정
- 기존 `docker-compose.yml` 수정

## 단계별 작업 계획

### 단계 1: 루트 build.gradle MSA화
- 변경 파일: `build.gradle` (루트)
- 변경 내용: `subprojects {}` 블록 전체 제거. `plugins {}` 를 `apply false` 방식으로 전환해 버전만 선언. `bootJar`/`jar` 블록 제거. `allprojects {}` 유지.
- 검증 방법: `./gradlew.bat projects`
- 롤백 방법: `git checkout build.gradle`
- 예상 소요: 짧음

### 단계 2: 각 서버 build.gradle 독립화 (serverA/B/C/aiops)
- 변경 파일: `serverA/build.gradle`, `serverB/build.gradle`, `serverC/build.gradle`, `aiops/build.gradle`
- 변경 내용: 각 파일에 `plugins {}` (java, jacoco, spring-boot, dependency-management), `java { toolchain }`, 공통 deps (web, validation, lombok, testcontainers), `jacocoTestReport {}` 추가. serverB는 `testcontainers:mysql` 제외.
- 검증 방법: `./gradlew.bat build -x test`
- 롤백 방법: `git checkout serverA/build.gradle serverB/build.gradle serverC/build.gradle aiops/build.gradle`
- 예상 소요: 보통

### 단계 3: docker-compose.infra.yml 생성
- 변경 파일: `docker-compose.infra.yml` (신규)
- 변경 내용: mysql, mysql-c, redis, redis-b, kafka, kafka-connect, debezium-init, redpanda-console 포함. 로컬 bridge network 사용 (external 아님).
- 검증 방법: `docker compose -f docker-compose.infra.yml config`
- 롤백 방법: 파일 삭제
- 예상 소요: 보통

### 단계 4: docker-compose.monitoring.yml 생성
- 변경 파일: `docker-compose.monitoring.yml` (신규)
- 변경 내용: otel-collector, prometheus, grafana, alertmanager, tempo, vector, loki, 각종 exporter, cadvisor, aiops 포함. shared-logs volume 제거. vector는 볼륨 mount 없이 유지 (K8s 전환 시 재설정). 로컬 bridge network 사용.
- 검증 방법: `docker compose -f docker-compose.monitoring.yml config`
- 롤백 방법: 파일 삭제
- 예상 소요: 보통

## 리스크 및 대응
- **prometheus scrape target**: EC2 배포 시 서비스명 대신 private IP로 변경 필요 → 별도 수동 작업 (IP 확정 후)
- **vector log 수집 미완**: stdout 전환 후 K8s 배포 시 DaemonSet이 자동 수집 → 현재는 vector 설정 비활성 상태로 유지
- **app 서버 간 통신 (server-b → server-a)**: `EXTERNAL_SERVER_A_URL`에 server-a EC2 private IP 지정 필요

## 배포 참고 (EC2)
```bash
# infra EC2
docker compose -f docker-compose.infra.yml up -d

# app EC2 (각각)
docker run -d -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://<infra-ip>:3307/promotion... \
  -e SPRING_DATA_REDIS_HOST=<infra-ip> \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=<infra-ip>:9092 \
  -e MANAGEMENT_OTLP_TRACING_ENDPOINT=http://<monitoring-ip>:4318/v1/traces \
  server-a:latest

# monitoring EC2
docker compose -f docker-compose.monitoring.yml up -d
```
