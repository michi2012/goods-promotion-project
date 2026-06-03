# 계획서: 모니터링 오류 수정 (SLI·Burn Rate·알람 정합성)

- 작성일: 2026-06-03

## 목표
alert-rules.yml과 sre-dashboard.json에서 확인된 4개 오류(SLI 타겟 불일치, 중복 알람, 티어 배치 오류, 에러 예산 윈도우)와 2개 개선(Burn Rate 이중 윈도우, for 과민 반응)을 수정한다.

## 성공 기준
- [ ] Tier 1 SLI 패널이 HTTP 2xx 대신 Saga 완료율(business_payment_success_total / business_payment_attempts_total)을 표시한다
- [ ] Burn Rate 알람이 1h+5m(14.4x) / 6h+30m(6x) 이중 윈도우로 동작하며 Saga 기반 recording rule을 사용한다
- [ ] 에러 예산 패널이 30d recording rule 기반으로 계산된다
- [ ] RefundFatalError 알람이 제거되어 중복 발화가 없다
- [ ] RedisDown, DatabaseConnectionPoolExhaustion이 Tier4-Infrastructure 그룹에 있다
- [ ] InstanceDownOrAvailabilityDrop, JvmHeapMemoryHigh, DatabaseConnectionPoolExhaustion의 for가 1m 이상이다

## 비범위 (Out of Scope)
- prometheus.yml retention 설정 변경
- InstanceDownOrAvailabilityDrop expr 변경 (HTTP 기반 인프라 가용성 알람으로 유지)
- 대시보드 패널 레이아웃·색상·threshold 변경
- Tier 6 명칭 통일

## 단계별 작업 계획

### 단계 1: recording-rules.yml 생성 + prometheus.yml 참조 추가
- 변경 파일: monitoring/prometheus/recording-rules.yml (신규), monitoring/prometheus/prometheus.yml
- 변경 내용: 5개 윈도우(5m·30m·1h·6h·30d) Saga 에러율 recording rule 작성. prometheus.yml rule_files에 경로 추가
- 검증 방법: YAML 구조 육안 확인
- 롤백 방법: 파일 삭제 + prometheus.yml rule_files 항목 제거
- 예상 소요: 짧음

### 단계 2: alert-rules.yml 수정
- 변경 파일: monitoring/prometheus/alert-rules.yml
- 변경 내용:
  1. RefundFatalError 제거
  2. HighErrorBurnRate → HighErrorBurnRateFast(1h+5m, 14.4x) + HighErrorBurnRateSlow(6h+30m, 6x)로 교체, recording rule 기반 expr
  3. RedisDown, DatabaseConnectionPoolExhaustion → Tier4-Infrastructure 그룹으로 이동
  4. for 조정: InstanceDownOrAvailabilityDrop·JvmHeapMemoryHigh·DatabaseConnectionPoolExhaustion → 1m
- 검증 방법: YAML 구조 육안 확인
- 롤백 방법: git checkout
- 예상 소요: 보통

### 단계 3: sre-dashboard.json 수정
- 변경 파일: monitoring/grafana/dashboards/sre-dashboard.json
- 변경 내용:
  1. 패널 1 expr: HTTP 2xx → Saga 완료율
  2. 패널 2 expr: saga:payment_error_rate:ratio_rate5m recording rule 기반
  3. 패널 3 expr: saga:payment_error_rate:ratio_rate30d recording rule 기반
  4. 패널 1 제목: "결제 API 성공률 (Availability)" → "결제 Saga 완료율 (SLI)"
- 검증 방법: JSON 구조 육안 확인
- 롤백 방법: git checkout
- 예상 소요: 짧음

## 리스크 및 대응
- recording rule 30d: Prometheus 데이터가 30일 미만이면 부정확 → 운영 환경에서 retention=30d 설정 필요 (이번 범위 외)
- div/0: attempts가 0인 구간에서 NaN 반환 → 알람 미발화라 안전. 대시보드는 `or vector(100)` 방어

## 의존성
- recording rule 메트릭 이름이 alert-rules.yml expr과 정확히 일치해야 함
- 단계 1 → 단계 2 순서 필수
