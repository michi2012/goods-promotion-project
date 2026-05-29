# 계획서: mysql-exporter 추가 및 Grafana Alertmanager datasource 연결

- 작성일: 2026-05-29

## 목표
mysql-exporter 두 개(promotion DB, payment DB)를 Prometheus 수집 파이프라인에 추가하고,
Grafana에 Alertmanager datasource를 연결해 알람 발화 상태를 시각화할 수 있게 한다.

## 성공 기준
- [ ] `docker-compose up -d` 후 `docker ps`에서 mysql-a-exporter, mysql-c-exporter 컨테이너가 Up 상태
- [ ] `curl http://localhost:9104/metrics` 및 `curl http://localhost:9105/metrics`에서 `mysql_up 1` 확인
- [ ] Prometheus UI(`http://localhost:9090/targets`)에서 mysql job 두 타겟 모두 State=UP
- [ ] Grafana > Connections > Data sources에서 Alertmanager datasource가 "Data source connected" 표시

## 비범위 (Out of Scope)
- MySQL 전용 Grafana 대시보드 패널 추가 (exporter 연결만 목표)
- Alertmanager 알람 룰 추가/수정
- MySQL 전용 모니터링 계정 생성 (포트폴리오 환경에서 root 사용)

## 단계별 작업 계획

### 단계 1: docker-compose.yml에 mysql-a-exporter, mysql-c-exporter 서비스 추가
- 변경 파일: `docker-compose.yml`
- 변경 내용: redis-exporter 패턴 그대로 mysql-a-exporter(9104), mysql-c-exporter(9105) 서비스 추가.
  DATA_SOURCE_NAME=root:root@(mysql:3306)/ 형식으로 credentials 주입.
- 검증 방법: `docker-compose up -d mysql-a-exporter mysql-c-exporter` 후 `docker ps` 확인
- 롤백 방법: 추가한 두 서비스 블록 삭제
- 예상 소요: 짧음

### 단계 2: prometheus.yml에 mysql scrape job 추가
- 변경 파일: `monitoring/prometheus/prometheus.yml`
- 변경 내용: job_name=mysql 추가. mysql-a-exporter:9104(instance=mysql-a), mysql-c-exporter:9104(instance=mysql-c) 두 타겟.
- 검증 방법: Prometheus 재시작 후 http://localhost:9090/targets 에서 mysql job UP 확인
- 롤백 방법: 추가한 job 블록 삭제
- 예상 소요: 짧음

### 단계 3: Grafana datasource.yml에 Alertmanager 추가
- 변경 파일: `monitoring/grafana/provisioning/datasources/datasource.yml`
- 변경 내용: type=alertmanager, uid=alertmanager, url=http://alertmanager:9093 datasource 추가
- 검증 방법: Grafana 재시작 후 Data sources 목록에서 "Data source connected" 확인
- 롤백 방법: 추가한 datasource 블록 삭제
- 예상 소요: 짧음

### 단계 4: infra-diagram.md 업데이트
- 변경 파일: `docs/infra-diagram.md`
- 변경 내용: 서비스 토폴로지 다이어그램에 mysql-a-exporter, mysql-c-exporter 노드 추가
- 검증 방법: Mermaid 문법 이상 없는지 육안 확인
- 롤백 방법: 추가한 노드 제거
- 예상 소요: 짧음

## 리스크 및 대응
- mysql-exporter가 MySQL 기동 전 시작되면 연결 실패 → depends_on mysql/mysql-c (service_healthy) 로 대응
- 리소스 초과 → redis-exporter와 동일한 0.25 CPU / 128M 적용

## 의존성
- mysql, mysql-c 컨테이너가 healthy 상태여야 exporter 기동됨
- alertmanager 컨테이너가 기동되어 있어야 Grafana datasource 연결 성공
