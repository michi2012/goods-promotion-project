# 체크리스트: aiops 모니터링 연동

- 마지막 업데이트: 2026-06-03

## 진행 상황

- [x] 단계 1: aiops/build.gradle 의존성 추가
  - [x] actuator, micrometer-prometheus, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, logstash-logback-encoder 포함 확인

- [x] 단계 2: application.yaml management 섹션 추가
  - [x] prometheus 엔드포인트 노출 확인
  - [x] tracing sampling 0.01, OTLP endpoint 확인

- [x] 단계 3: logback-spring.xml 생성
  - [x] CONSOLE + ASYNC_FILE 구조 확인
  - [x] LogstashEncoder, APP_NAME=aiops 확인
  - [x] kafka logger 없음 확인 (serverA diff 검증)

- [x] 단계 4: prometheus.yml scrape job 추가
  - [x] aiops job (target: aiops:8085) 확인

- [x] 단계 5: docker-compose.yml aiops 서비스 보완
  - [x] shared-logs:/logs 볼륨 확인
  - [x] TZ: Asia/Seoul 확인
  - [x] MANAGEMENT_OTLP_TRACING_ENDPOINT 확인
  - [x] otel-collector depends_on 확인

## 최종 검증
- [ ] `./gradlew :aiops:build` 성공 (사용자 실행)
- [x] 의도하지 않은 파일 변경 없음

## 발견 사항
- docker-compose.yml aiops 환경변수가 기존에 `-` 리스트 형식이었으나 다른 서버들과 맞춰 `key: value` 맵 형식으로 통일함
