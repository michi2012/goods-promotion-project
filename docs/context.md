# 맥락 노트: cAdvisor 추가 및 MySQL CPU/메모리 모니터링 완성

## 왜 이 방식을 선택했는가
Redis 대시보드에는 CPU·메모리 패널이 있으나 MySQL은 없어 비대칭이 발생했다. redis_exporter는 Redis INFO 명령에서 CPU·메모리를 직접 파싱하지만, mysqld_exporter는 OS 수준 지표를 수집하지 않는다. 컨테이너 수준 CPU/메모리 수집의 표준 방법인 cAdvisor를 추가해 비대칭을 해소하기로 했다. 사용자 확인: 포트폴리오 목적이므로 Linux 표준 마운트 방식으로 작성하고 Windows 주의사항을 주석으로 명시한다.

## 검토했으나 채택하지 않은 대안

### 대안 A: Redis CPU/메모리 패널 제거
- 무엇: Redis 패널도 없애서 일관성 맞춤
- 왜 안 썼나: 이미 수집 중인 정보를 버리는 것은 모니터링 품질 하락

### 대안 B: Docker socket만 마운트
- 무엇: `/var/run/docker.sock`만 마운트해 Windows 호환성 확보
- 왜 안 썼나: 일부 메트릭 누락 가능, 포트폴리오는 Linux 서버 배포가 기준

### 대안 C: cAdvisor 없이 넘어가기
- 무엇: MySQL 패널 없이 현 상태 유지
- 왜 안 썼나: Redis와의 비대칭이 면접/리뷰에서 "빠뜨린 것"처럼 보일 수 있음

## 관련 파일/위치
- `docker-compose.yml` — cAdvisor 서비스 추가
- `monitoring/prometheus/prometheus.yml` — cadvisor scrape 설정
- `monitoring/grafana/dashboards/sre-dashboard.json` — Tier4 MySQL 패널 추가
- `monitoring/prometheus/alert-rules.yml` — MySQL CPU/메모리 알람 추가

## cAdvisor 메트릭 참고
- CPU: `rate(container_cpu_usage_seconds_total{name=~"promotion-mysql.*"}[1m]) * 100`
- Memory: `container_memory_working_set_bytes{name=~"promotion-mysql.*"} / container_spec_memory_limit_bytes{name=~"promotion-mysql.*"} * 100`
- `container_spec_memory_limit_bytes`가 0인 경우(제한 없음) 별도 필터 필요
