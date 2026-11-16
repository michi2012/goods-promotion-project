# 계획서: serverC payment-cancel DLT 기록 실패 대비 (BL-034)

- 작성일: 2026-11-16
- 관련 이슈/티켓: BL-034 (`docs/backlog.md`), PR #48 리뷰 C1 [필수]·C2·C4. 정책 원본: `docs/specs/user-refund.md`

## [진행 중] BL-034

### 목표
PG 취소가 Kafka 재시도를 다 쓰고 DLT에 왔을 때, 실패 기록(`payment_cancel_failures`)을 DB에 쓰지 못해도 그 건이 알람 없이 사라지지 않게 한다. 함께 P0 알람이 이미 아는 건 때문에 다시 울리는 문제(C2)와, 다시 열린 실패 건의 재시도 횟수(C4)를 바로잡는다.

### 성공 기준
- [ ] DLT 핸들러가 계속 실패하면 정해진 횟수만큼 재시도한 뒤 `business_payment_cancel_dlt_record_failed_total`이 1 오르고, JSON 파싱 오류는 재시도 없이 바로 오른다 (`KafkaConsumerConfigTest`)
- [ ] `business_payment_pg_refund_fatal_total`은 DLT 기록이 성공했을 때만 오른다 (`PaymentCancelConsumerTest`)
- [ ] 이미 EXHAUSTED인 행을 다시 EXHAUSTED로 기록하면 `business_payment_cancel_retry_exhausted_total`이 오르지 않는다 (`PaymentCancelServiceTest`)
- [ ] RESOLVED 행이 DLT에 다시 도착하면 attempts=0인 RETRYING이 된다 (`PaymentCancelFailureTest`)
- [ ] P0 `PaymentFatalError`가 두 메트릭 중 하나라도 늘면 울린다 (알람 규칙 2개 파일 동일, YAML 파싱 + `helm template` 통과)

### 비범위 (Out of Scope)
- serverA·serverB DLT 팩토리의 같은 `FixedBackOff(0,0)` 패턴 → 발견 사항으로 백로그 이전
- Grafana 패널 정리 (BL-031)
- 최종 실패 건의 자동 복구 (사람이 로그·DLT 토픽 원본으로 처리)
- BL-029 PR② 이후 범위 전부

### 단계별 작업 계획

#### 단계 1: C2·C4 — 엔티티·서비스
- 변경 파일: `serverC/.../entity/PaymentCancelFailure.java`, `serverC/.../service/PaymentCancelService.java`, 테스트 `PaymentCancelFailureTest.java`, `PaymentCancelServiceTest.java`
- 변경 내용: `recordDltArrival`이 RESOLVED → RETRYING일 때 attempts를 0으로 초기화. `recordNeedsAdmin`은 행이 없거나 EXHAUSTED가 아니던 경우에만 `needsAdmin`을 올린다(이미 EXHAUSTED면 사유만 갱신).
- 검증: `.\gradlew.bat :serverC:test --tests "*PaymentCancelFailureTest" --tests "*PaymentCancelServiceTest"`
- 롤백: `git checkout -- <위 파일>`
- 예상 소요: 짧음

#### 단계 2: C1 — DLT 핸들러 재시도 + 최종 실패 메트릭
- 변경 파일: `serverC/.../config/KafkaConsumerConfig.java`, `serverC/.../kafka/PaymentCancelConsumer.java`, 테스트 `KafkaConsumerConfigTest.java`(신규), `PaymentCancelConsumerTest.java`
- 변경 내용: DLT 팩토리 백오프를 지수 5회(1→2→4→8→16초, 약 31초)로 바꾸고, `JsonProcessingException`은 재시도하지 않는다. 최종 실패 시 `business_payment_cancel_dlt_record_failed_total`(빈 생성 시 등록)을 올리고 원본 payload를 에러 로그로 남긴다. `handleDlt`의 `refundFatal.increment()`는 `recordDltArrival` 성공 뒤로 옮긴다(재시도 시 중복 집계 방지).
- 검증: `.\gradlew.bat :serverC:test --tests "*KafkaConsumerConfigTest" --tests "*PaymentCancelConsumerTest"`
- 롤백: `git checkout -- <위 파일>`, 신규 테스트 삭제
- 예상 소요: 보통

#### 단계 3: P0 알람 규칙
- 변경 파일: `monitoring/prometheus/alert-rules.yml`, `helm/promotion-monitoring/files/alert-rules.yml`
- 변경 내용: `PaymentFatalError` expr에 `or increase(business_payment_cancel_dlt_record_failed_total[1m]) > 0` 추가, description에 "DLT 기록 실패 시 serverC 로그의 payload로 확인" 추가.
- 검증: 두 파일 YAML 파싱(Node) + `helm template helm/promotion-monitoring` 통과, 두 파일 해당 블록 동일 확인 (`promtool`이 있으면 `promtool check rules`)
- 롤백: `git checkout -- <위 파일>`
- 예상 소요: 짧음

#### 단계 4: 명세 갱신
- 변경 파일: `docs/specs/user-refund.md`
- 변경 내용: 상태 전이 표의 RESOLVED → RETRYING을 "attempts=0"으로, "장애 시 동작"에 "DLT 기록 실패 → 5회 재시도 → 메트릭 + P0" 행 추가, C2 동작(이미 EXHAUSTED면 알람 없음) 반영, 변경 이력 행 추가.
- 검증: 육안 (코드 동작과 표 일치)
- 롤백: `git checkout -- docs/specs/user-refund.md`
- 예상 소요: 짧음

#### 단계 5: 전체 빌드 + 로컬 E2E 회귀
- 검증: `.\gradlew.bat :serverC:build` (백그라운드). 로컬 스택이 떠 있으면 `backend-e2e/`의 serverC 관리자 API 시나리오 포함 회귀 실행, 없으면 기동 여부를 사용자에게 확인 (새 마이그레이션 없음)
- 롤백: 해당 없음
- 예상 소요: 보통

### 리스크 및 대응
- 리스크: 테스트에서 실제 백오프(약 31초)를 기다리게 됨 → 대응: mock 컨테이너(`isRunning()=false`)로 대기를 건너뛰는 방식을 먼저 시도, 안 되면 팩토리 생성 로직에서 백오프를 인자로 받는 package-private 메서드로 분리
- 리스크: 백오프 동안 DLT 컨슈머 스레드가 막힘 → 대응: 최대 31초로 `max.poll.interval.ms`(기본 5분)보다 훨씬 짧고, DLT 팩토리를 쓰는 리스너는 `payment-cancel.DLT` 하나뿐
- 리스크: 새 메트릭을 처음 증가할 때 등록하면 Prometheus `increase()`가 첫 증가를 놓침 → 대응: 빈 생성 시 0으로 등록

### 의존성
- 새 의존성 없음 (`spring-kafka-test`·Micrometer 기존)
- BL-029 PR②가 이 경로에 의존 — 이 작업 머지 후 착수
