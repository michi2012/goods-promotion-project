# 계획서: cAdvisor 추가 및 MySQL CPU/메모리 모니터링 완성

- 작성일: 2026-05-29

## 목표
cAdvisor를 스택에 추가해 MySQL 컨테이너 CPU/메모리 메트릭을 수집하고, Grafana 대시보드와 Prometheus 알람에 반영해 Redis와의 모니터링 비대칭을 해소한다.

## 성공 기준
- [ ] `docker-compose up` 후 cAdvisor 컨테이너 정상 기동, `http://localhost:8090` 접속 가능
- [ ] Prometheus `http://localhost:9090/targets`에서 cadvisor job이 UP 상태
- [ ] Grafana Tier4 MySQL 섹션에 CPU·Memory 패널 2개 추가, 데이터 표시
- [ ] alert-rules.yml에 MySQL CPU/메모리 알람 2개 추가, Prometheus 규칙 로드 오류 없음

## 비범위 (Out of Scope)
- MySQL 외 다른 컨테이너의 cAdvisor 메트릭을 대시보드/알람에 추가하는 것 (각 exporter로 커버됨)
- cAdvisor 자체 메트릭 대시보드 구성
- Windows 로컬 환경 전용 docker-compose 분기 파일 생성

## 단계별 작업 계획

### 단계 1: docker-compose.yml에 cAdvisor 서비스 추가
- 변경 파일: `docker-compose.yml`
- 변경 내용: SRE 스택 섹션 하단에 cadvisor 서비스 추가. Linux 표준 마운트(`/:/rootfs:ro` 등) + `privileged: true`. Windows 주의사항 주석 명시. 포트 `8090:8080`.
- 검증 방법: `docker-compose config` — 파싱 오류 없음 확인
- 롤백 방법: cadvisor 서비스 블록 삭제
- 예상 소요: 짧음

### 단계 2: prometheus.yml에 cadvisor scrape 설정 추가
- 변경 파일: `monitoring/prometheus/prometheus.yml`
- 변경 내용: `job_name: 'cadvisor'` scrape 설정 추가, 타겟 `cadvisor:8080`
- 검증 방법: `docker-compose config` 파싱 오류 없음
- 롤백 방법: cadvisor job 블록 삭제
- 예상 소요: 짧음

### 단계 3: Grafana 대시보드에 MySQL CPU/메모리 패널 추가
- 변경 파일: `monitoring/grafana/dashboards/sre-dashboard.json`
- 변경 내용: Tier4 MySQL 섹션 끝에 패널 2개 추가
  - `27. [MySQL] CPU 사용률` — `rate(container_cpu_usage_seconds_total{name=~"promotion-mysql.*"}[1m]) * 100`
  - `28. [MySQL] 메모리 사용률` — `container_memory_working_set_bytes / container_spec_memory_limit_bytes * 100` (limit > 0 필터)
- 검증 방법: `Get-Content sre-dashboard.json | ConvertFrom-Json` — JSON 파싱 오류 없음
- 롤백 방법: 추가한 2개 패널 객체 제거
- 예상 소요: 보통

### 단계 4: alert-rules.yml에 MySQL CPU/메모리 알람 추가 및 번호 재정리
- 변경 파일: `monitoring/prometheus/alert-rules.yml`
- 변경 내용: Tier4 끝(16번 뒤)에 17·18번 추가, Tier5 기존 17~20번 → 19~22번으로 재조정
- 검증 방법: YAML 파싱 오류 없음
- 롤백 방법: 추가 알람 제거, 번호 원복
- 예상 소요: 짧음

## 리스크 및 대응
- **Windows 로컬 실행 시 cAdvisor 메트릭 미수집**: WSL2 마운트 경로 차이로 데이터 비어 있을 수 있음 → docker-compose 주석으로 명시, 패널은 "No data"로 렌더링됨 (오류 아님)
- **`container_spec_memory_limit_bytes` 값 0**: 메모리 제한 미설정 시 0으로 나와 나누기 오류 → `> 0` 조건 필터링

## 의존성
- 단계 1 완료 후 단계 2 가능 (cadvisor 서비스명이 prometheus scrape 타겟에 사용됨)
- 단계 3·4는 단계 1·2와 독립적으로 작성 가능
