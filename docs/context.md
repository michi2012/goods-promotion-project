# 맥락 노트: mysql-exporter 추가 및 Grafana Alertmanager datasource 연결

## 왜 이 방식을 선택했는가

기존에 DB 상태 감지는 HikariCP(hikaricp_connections_pending)로 간접 측정하고 있었다.
이는 "커넥션이 포화된 뒤에야 알람 발화"하는 구조라 선제 감지가 어렵다.
mysql-exporter를 추가하면 innodb 버퍼풀 히트율, 슬로우쿼리 추이, 커넥션 수 등
MySQL 내부 상태를 Prometheus에서 직접 수집할 수 있다.

Alertmanager datasource는 Grafana에서 현재 Firing 중인 알람 목록과 히스토리를
시각화하기 위해 추가한다. 실제 알람 처리는 mcp → Slack 경로이지만,
Grafana 대시보드에서 알람 상태를 한눈에 확인할 수 있는 창구가 없었다.

## 검토했으나 채택하지 않은 대안

### 대안 A: MySQL 전용 모니터링 계정 생성
- 무엇: `exporter` 계정을 별도 생성해 최소 권한(PROCESS, REPLICATION CLIENT, SELECT)만 부여
- 왜 안 썼나: 포트폴리오 단일 호스트 Docker 환경에서 root 사용이 간단하고 실용적.
  운영 환경이라면 전용 계정이 필수지만 이 프로젝트의 범위를 벗어남.

### 대안 B: mysqld-exporter를 단일 컨테이너로 multi-target 구성
- 무엇: 하나의 exporter 컨테이너에서 두 DB를 모두 수집
- 왜 안 썼나: prom/mysqld-exporter v0.15+ 에서 multi-target 지원하지만 설정이 복잡함.
  redis-exporter 패턴(DB별 컨테이너 분리)과 일관성을 유지하는 게 코드베이스 컨벤션에 맞음.

## 기존 코드베이스 컨벤션
- exporter 패턴: DB/인프라 컴포넌트마다 전용 exporter 컨테이너 분리 (redis-exporter, redis-b-exporter, kafka-exporter)
- 리소스 제한: exporter류는 0.25 CPU / 128M (docker-compose.yml 참고)
- Prometheus label: instance 레이블로 exporter 구분 (instance=redis-a, redis-b 패턴 참고)
- Grafana datasource uid: 소문자 단어 (prometheus, tempo, loki 패턴)

## 관련 파일/위치
- `docker-compose.yml` — 전체 서비스 정의
- `monitoring/prometheus/prometheus.yml` — scrape job 정의
- `monitoring/grafana/provisioning/datasources/datasource.yml` — Grafana datasource 자동 프로비저닝
- `docs/infra-diagram.md` — 인프라 토폴로지 다이어그램 (업데이트 필요)
