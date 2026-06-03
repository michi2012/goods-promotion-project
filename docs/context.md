# 맥락 노트: aiops 모니터링 연동

## 왜 이 방식을 선택했는가

### 기존 서버 템플릿 그대로 적용
serverA/B/C가 이미 동일한 스택(Actuator + Micrometer + OTLP + Loki)을 사용하므로
aiops도 동일 패턴을 따른다. 일관성을 위해 의존성 버전도 동일하게 맞춘다.

### 별도 prometheus scrape job
aiops는 포트 8085를 사용하므로 기존 `promotion-api` job(8080/8081/8082)에 병합하면
Prometheus가 연결 실패를 기록한다. 별도 job `aiops`로 분리하는 것이 명확하다.

### 제외한 의존성
- `datasource-micrometer-spring-boot`: DB 연결이 없으므로 불필요
- `resilience4j-micrometer`: Resilience4j 미사용
- logback `org.apache.kafka` logger: Kafka 미사용

## 기존 코드베이스 컨벤션
- logback 템플릿: `serverA/src/main/resources/logback-spring.xml` 기준
- management 설정: `serverA/src/main/resources/application.yaml` management 섹션 기준
- tracing sampling: 0.01 (1%) — 전 서버 공통

## 관련 파일/위치
- `aiops/build.gradle` — 의존성
- `aiops/src/main/resources/application.yaml` — management 설정
- `aiops/src/main/resources/logback-spring.xml` — 구조화 로그 (Loki 수집 대상)
- `monitoring/prometheus/prometheus.yml` — scrape 설정
- `docker-compose.yml` — shared-logs 볼륨, OTLP endpoint 환경변수
