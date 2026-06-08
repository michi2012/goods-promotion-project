# 맥락 노트: 결제 흐름 종단 간 p95 지연 알림 규칙 구현 (MIC-8)

## 왜 이 방식을 선택했는가
- 출발점은 실제 의문점이었다 — "결제 흐름(서버 A→B→C, C에서 결제 후 A로 최종 응답+DB 업데이트)에서 p95 > 500ms를 알림 기준으로 삼는 게 맞는가?"
- `/spec-draft` → `/spec-to-tickets` 과정에서 명세서를 작성하며 **임계치를 500ms가 아닌 2초로 조정**하기로 했다 — 이유: 이 흐름은 단일 서비스 응답이 아니라 Kafka 기반 비동기 사가(서버 A 진입 → B/C 비동기 처리 → A의 최종 PAID 확정까지)의 종단 간(end-to-end) 시간이라서, 단일 HTTP 요청 기준 500ms보다 현실적인 임계치가 필요했다.
- 명세서 작성 중 "종단 간 지연을 측정하는 메트릭이 이미 있다"는 가정이 **틀렸음**을 코드 조사로 확인했다 — `serverA`에는 `business_payment_*` 계열의 Counter만 있고, 종단 간 소요 시간을 재는 Timer/Histogram이 없다. 기존 `http_server_requests_seconds`는 단일 HTTP 요청 기준이라 비동기 사가의 종단 간 시간과 다르다.
- 이 사실을 사용자에게 투명하게 보고했고("발견 사항" 형식), 사용자는 **계측 코드 작성까지 범위에 포함**하기로 결정했다 ("b로가자 2번은 너 방식이 맞아" — 옵션 B + 계측 위치(SagaOrchestratorService의 사가 시작~완료) 제안 확정).
- 계측 위치를 정하면서, 이미 Redis에 사가 시작 시각(`createdAt`)이 저장되어 `SagaStateService.getCreatedAt(orderId)`로 조회 가능하다는 점을 발견했다 — 새로운 상태 저장 없이 `tryCompleteSaga()`의 PAID 확정 성공 시점에서 `현재시각 - createdAt`으로 종단 간 시간을 계산하면 된다.
- 측정 대상은 **성공(PAID 확정) 경로로만 한정**하기로 했다 — 사용자 승인("y, 그 방향으로 진행해서 plan.md 작성해줘"). 실패·타임아웃 사가는 이미 `SagaExpired` 등 별도 알림이 담당하므로 섞으면 의미가 흐려진다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 기존 `http_server_requests_seconds`(HTTP 요청 시간) 메트릭을 그대로 활용
- 무엇: 새 계측 없이 기존 `PaymentHighLatency` 알림 패턴을 그대로 복제해 임계치만 2초로 바꾼다.
- 왜 안 썼나: 사가는 Kafka 기반 비동기 흐름이라 단일 HTTP 요청의 응답 시간이 "결제 시작 → 최종 PAID 확정"까지의 종단 간 시간을 대표하지 못한다. 알림이 실제로 측정하려는 대상과 메트릭이 어긋나 "거짓 안전감"을 줄 위험이 있다.

### 대안 B: AOP/`@Observed`로 자동 계측
- 무엇: Spring AOP나 Micrometer `@Observed` 애너테이션으로 메서드 실행 시간을 자동 계측한다.
- 왜 안 썼나: 사가는 여러 비동기 컨슈머(`SagaResultConsumer` 등)에 걸쳐 진행되며 단일 메서드 호출로 끝나지 않는다. 시작 시각과 종료 시각이 서로 다른 스레드/컨슈머에서 발생하므로 AOP의 메서드 단위 자동 계측으로는 종단 간 시간을 잴 수 없다 — 명시적으로 시작 시각을 저장해두고 완료 시점에서 차이를 계산하는 수동 계측이 필요하다.

## 기존 코드베이스 컨벤션
- 메트릭 초기화: `@PostConstruct void initMetrics()` 안에서 `Counter.builder("business_payment_xxx").description(...).tag(...).register(meterRegistry)` (`serverC/PaymentService.java:25-53`) — 신규 Timer도 동일한 패턴(`@PostConstruct` + `.builder(...).description(...).register(meterRegistry)`)으로 초기화한다.
- 사가 시작 시각 저장: `SagaStateService`가 Redis 해시에 `createdAt`(epoch millis, `System.currentTimeMillis()`)을 저장하고(`SagaStateService.java:30`), `getCreatedAt(orderId)`로 조회한다(`SagaStateService.java:80`) — 이미 `SagaTimeoutScheduler`의 3분 타임아웃 체크에 사용 중인 검증된 경로다.
- 알림 규칙: `helm/promotion-monitoring/files/alert-rules.yml`의 `Tier2-Application-Performance` 그룹, `PaymentHighLatency`(line 86)가 `histogram_quantile(0.95, ...) > {임계치}` 와 `sum(rate(..._count[5m])) > 0` 을 `and`로 묶는 패턴 — 신규 규칙도 동일 패턴을 따른다.

## 관련 파일/위치
- `serverA/src/main/java/promotion/serverA/service/SagaOrchestratorService.java` — `tryCompleteSaga()`(line 34)가 PAID 확정 + DB 업데이트를 수행하는 지점. 여기서 `updated > 0` 직후에 종단 간 시간을 기록한다.
- `serverA/src/main/java/promotion/serverA/service/SagaStateService.java` — `getCreatedAt(orderId)`(line 80)로 사가 시작 시각(Redis 저장)을 조회.
- `serverC/src/main/java/promotion/serverC/service/PaymentService.java` — Micrometer `Counter` 초기화 컨벤션의 참조 예시(line 25-53). 신규 `Timer`도 동일 패턴 적용.
- `helm/promotion-monitoring/files/alert-rules.yml` (line 86 `PaymentHighLatency`) / `monitoring/prometheus/alert-rules.yml` — 두 파일이 각각 Helm 배포용 / docker-compose 로컬용으로 병행 관리되며 동기화 상태를 유지해야 한다.
- `serverA/src/test/java/promotion/serverA/service/SagaOrchestratorServiceTest.java` — 신규 Timer 등록·기록 검증 케이스를 추가할 기존 테스트 파일.

## 외부 참조
- Linear MIC-8: https://linear.app/michi2012/issue/MIC-8 (이슈 본문에 임계치 2초 결정 배경 포함)
- Linear MIC-9: MIC-8의 서브태스크 — 실측 데이터 누적 후 임계치 재검토 (이번 작업 범위 밖, 후속 작업)
