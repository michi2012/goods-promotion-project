# 체크리스트: 모니터링 오류 수정

- 마지막 업데이트: 2026-06-03

## 진행 상황

- [x] 단계 1: recording-rules.yml 생성 + prometheus.yml 참조 추가
  - [x] 5개 윈도우 recording rule 작성 (5m·30m·1h·6h·30d)
  - [x] prometheus.yml rule_files에 경로 추가
  - [x] YAML 구조 육안 확인

- [x] 단계 2: alert-rules.yml 수정
  - [x] RefundFatalError 제거
  - [x] HighErrorBurnRate → HighErrorBurnRateFast/Slow 이중 윈도우로 교체
  - [x] RedisDown → Tier4-Infrastructure 그룹으로 이동
  - [x] DatabaseConnectionPoolExhaustion → Tier4-Infrastructure 그룹으로 이동
  - [x] InstanceDownOrAvailabilityDrop for: 10s → 1m
  - [x] JvmHeapMemoryHigh for: 10s → 1m
  - [x] DatabaseConnectionPoolExhaustion for: 30s → 1m
  - [x] YAML 구조 육안 확인

- [x] 단계 3: sre-dashboard.json 수정
  - [x] 패널 1 제목 + expr 교체 (Saga 완료율)
  - [x] 패널 2 Burn Rate expr 교체 (recording rule 기반)
  - [x] 패널 3 Error Budget expr 교체 (30d recording rule 기반)
  - [x] JSON 구조 육안 확인

## 최종 검증
- [x] 변경 사항이 plan.md 비범위를 침범하지 않았는지 확인
- [x] recording rule 메트릭 이름이 alert-rules.yml expr과 정확히 일치하는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 확인

## 발견 사항
- Prometheus retention 기본값 15d → 30d recording rule 정확도 위해 운영 환경에서 별도 설정 필요 (이번 범위 외)
