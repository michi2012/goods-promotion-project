# 계획서: aiops 모니터링 연동

- 작성일: 2026-06-03

## 목표
aiops 서비스에 메트릭(Prometheus), 분산 트레이싱(OTLP/Tempo), 구조화 로그(Loki) 연동을 추가한다.
기존 serverA/B/C와 동일한 스택·템플릿을 사용하되, aiops에 없는 DB/Resilience4j 관련 의존성은 제외한다.

## 성공 기준
- [ ] `./gradlew :aiops:build` 성공 (사용자 실행)
- [ ] `aiops/build.gradle` 에 actuator, micrometer-prometheus, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, logstash-logback-encoder 포함
- [ ] `application.yaml` management 섹션에 prometheus 엔드포인트 노출, tracing sampling 0.01, OTLP endpoint 설정
- [ ] `logback-spring.xml` 이 serverA 템플릿과 동일 구조 (CONSOLE + ASYNC_FILE, LogstashEncoder)
- [ ] `prometheus.yml` 에 `aiops` scrape job 추가 (`aiops:8085`)
- [ ] `docker-compose.yml` aiops 서비스에 `shared-logs:/logs` 볼륨, `TZ`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `otel-collector` depends_on 추가

## 비범위 (Out of Scope)
- `datasource-micrometer-spring-boot` (DB 없음)
- `resilience4j-micrometer` (Resilience4j 없음)
- 커스텀 메트릭 추가 (기존 HTTP 자동 계측으로 충분)
- Grafana 대시보드 패널 추가

## 단계별 작업 계획

### 단계 1: aiops/build.gradle 의존성 추가
- 변경 파일: `aiops/build.gradle`
- 변경 내용: actuator, micrometer-registry-prometheus, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, logstash-logback-encoder:7.4 추가
- 검증 방법: `./gradlew :aiops:dependencies --configuration runtimeClasspath` 로 의존성 트리 확인
- 롤백 방법: 추가한 라인 제거
- 예상 소요: 짧음

### 단계 2: application.yaml management 섹션 추가
- 변경 파일: `aiops/src/main/resources/application.yaml`
- 변경 내용: management.endpoints(prometheus, health, info 노출), observations(/actuator/** 제외), metrics percentiles-histogram, tracing sampling 0.01, otlp endpoint 추가
- 검증 방법: 파일 내용 육안 확인 (serverA 대비 체크)
- 롤백 방법: 추가한 섹션 제거
- 예상 소요: 짧음

### 단계 3: logback-spring.xml 생성
- 변경 파일: `aiops/src/main/resources/logback-spring.xml` (신규)
- 변경 내용: serverA 템플릿 기반, `org.apache.kafka` logger 제거, APP_NAME defaultValue=aiops
- 검증 방법: serverA 파일과 diff 비교
- 롤백 방법: 파일 삭제
- 예상 소요: 짧음

### 단계 4: prometheus.yml scrape job 추가
- 변경 파일: `monitoring/prometheus/prometheus.yml`
- 변경 내용: `aiops` job 추가 (target: `aiops:8085`, path: `/actuator/prometheus`)
- 검증 방법: YAML 구조 육안 확인
- 롤백 방법: 추가한 job 블록 제거
- 예상 소요: 짧음

### 단계 5: docker-compose.yml aiops 서비스 보완
- 변경 파일: `docker-compose.yml`
- 변경 내용: aiops 서비스에 `volumes: [shared-logs:/logs]`, `TZ: Asia/Seoul`, `MANAGEMENT_OTLP_TRACING_ENDPOINT: http://otel-collector:4318/v1/traces`, `depends_on: otel-collector` 추가
- 검증 방법: `docker compose config` 로 YAML 유효성 확인 (사용자 실행)
- 롤백 방법: 추가한 항목 제거
- 예상 소요: 짧음

## 리스크 및 대응
- logstash-logback-encoder 버전 충돌 → 기존 serverA와 동일 버전(7.4) 사용으로 회피
- otel-collector depends_on 추가 시 컨테이너 기동 순서 지연 → restart: on-failure 이미 설정되어 있어 허용 범위

## 의존성
- 단계 1(의존성) → 단계 2(설정) 순서 권장 (컴파일 오류 없이 설정값 검증 가능)
- 단계 4, 5는 인프라 설정이므로 순서 무관
