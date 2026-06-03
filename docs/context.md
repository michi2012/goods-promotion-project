# 맥락 노트: 모니터링 오류 수정

## 왜 이 방식을 선택했는가

### SLI 타겟: HTTP → Saga 기반
결제 흐름이 HTTP 202 반환 후 Kafka 비동기로 처리되므로 HTTP 2xx는 "요청 접수 여부"만 반영한다.
PAID/FAILED/EXPIRED는 Saga orchestrator가 결정하므로 진짜 결제 성공률은
`business_payment_success_total / business_payment_attempts_total`이다.
InstanceDownOrAvailabilityDrop 알람은 인프라 가용성 체크 목적이므로 HTTP 기반 유지.

### Recording Rules 별도 파일 분리
recording-rules.yml을 alert-rules.yml과 분리한 이유: 역할이 다르다.
recording rule은 "메트릭 사전 계산"이고 alert rule은 "조건 감시"다.
같은 파일에 두면 추후 rule 수가 늘어날 때 관리가 어려워진다.

### Burn Rate 이중 윈도우 (1h+5m / 6h+30m)
단일 5m 윈도우는 일시적 스파이크에 과민반응하고 장기 누수를 놓친다.
Google SRE 표준: 두 조건을 AND로 묶어 "긴 윈도우가 확인해주는 신호만 발화"하도록 설계.
- Fast: 1h > 14.4x AND 5m > 14.4x → 에러 예산 1시간 내 소진 예상
- Slow: 6h > 6x AND 30m > 6x → 에러 예산 5일 내 소진 예상

### for 시간 조정
- JvmHeapMemoryHigh 10s → 1m: GC 직후 Heap이 순간 90% 찍고 내려오는 패턴 존재. 10s면 정상 GC에도 발화.
- InstanceDownOrAvailabilityDrop 10s → 1m: 재배포·재시작 시 일시적 5xx 스파이크 방어.
- DatabaseConnectionPoolExhaustion 30s → 1m: 순간 대기는 정상 범위.

## 검토했으나 채택하지 않은 대안

### 대안 A: recording rule을 alert-rules.yml 내부 그룹으로 추가
- 무엇: 파일 추가 없이 같은 파일 상단에 recording rules 그룹 삽입
- 왜 안 썼나: 역할 혼재. prometheus.yml 수정도 불필요해 간단하지만 장기 유지보수 비용이 높다

### 대안 B: [7d] 슬라이딩으로 에러 예산 근사
- 무엇: [30d] 대신 [7d] range를 사용해 데이터 부족 문제 회피
- 왜 안 썼나: 사용자가 30d recording rule 방식을 명시적으로 요청

## 관련 파일/위치
- monitoring/prometheus/recording-rules.yml — Saga 에러율 5개 윈도우 사전 계산
- monitoring/prometheus/alert-rules.yml — 알람 규칙 (recording rule 메트릭 참조)
- monitoring/grafana/dashboards/sre-dashboard.json — Tier 1 SLI·Burn Rate·Error Budget 패널
- monitoring/prometheus/prometheus.yml — rule_files 참조
