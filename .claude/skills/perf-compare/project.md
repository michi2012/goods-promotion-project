# 성능 비교 적용 값 — promotion 프로젝트

범용 원칙은 같은 폴더의 `SKILL.md`. 이 파일은 이 프로젝트에만 해당하는 값이다.
비어 있는 칸은 첫 실측 때 실제 값으로 채운다.

## 위치
| 대상 | 위치 |
|------|------|
| 새 k6 스크립트 | `perf/k6/{주제}.js` |
| 결과 기록 | `docs/perf/YYYY-MM-DD-{주제}.md` (+ 같은 폴더에 k6 summary JSON) |
| 기존 시나리오 | `perf/k6/load-test.js`, `perf/k6/load-test2.js` — 선착순 구매 스파이크(1000 VU, shared-iterations). 응답 코드별 커스텀 카운터(202 성공·400 품절·503 큐 제한·기타) |

## 도구
- k6 로컬 설치됨 (v1.7.1)
- 모니터링: docker compose의 Prometheus(`monitoring/prometheus/prometheus.yml`) + Grafana. scrape job: `promotion-api`, `aiops`, `infrastructure`, `redis`, `kafka`, `mysql`, `cadvisor`
- 보조 지표 출처
  - 스레드·커넥션 풀·HTTP 지연: `promotion-api` (Spring Actuator/Micrometer)
  - Redis hit율·명령 수: `redis` exporter
  - consumer lag: `kafka` exporter
  - 쿼리 수·슬로 쿼리: `mysql` exporter, 쿼리 단위는 `EXPLAIN ANALYZE`
  - 컨테이너 CPU·메모리: `cadvisor`
  - 배치: (Spring Batch 도입 시 — BL-002) `BATCH_STEP_EXECUTION`

## 이 프로젝트에서 배운 것
- **구매 API는 202(접수) 후 비동기 Saga로 처리된다.** k6 TPS만 보면 안 된다. 과거 큐 한도 제거 시 응답 5,700 TPS였지만 DB 처리 완료까지 12분이 걸렸다. 재고가 모두 처리 완료되는 시점까지를 함께 잰다 — 과거에는 Grafana Saga 완료 메트릭으로 확인했다(현재 `sre-dashboard`의 "결제 Saga 완료율" 패널, recording rule `saga:payment_error_rate:*`). 완료 시점을 재는 정확한 쿼리는 첫 실측 때 확정해 여기에 적는다.
- 지금까지의 단계별 개선(60 → 10,300 TPS)은 로컬 docker compose에서 상대 비교로 측정했다. 과정과 수치는 `docs/qa/interview-qa-01-tps.md`.

## 환경
| 환경 | 상태 |
|------|------|
| 로컬 docker compose | 사용 가능. k6도 같은 머신에서 실행되므로 기록에 명시 |
| EC2 | (첫 클라우드 측정 시 인스턴스 타입·부하 발생기 구성 기록) |
| EKS | 재가동 전 |
