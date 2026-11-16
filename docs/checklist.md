# 체크리스트: serverC payment-cancel DLT 기록 실패 대비 (BL-034)

- 마지막 업데이트: 2026-11-16

## [완료] BL-034 (PR #51)

### 진행 상황
- [x] 단계 1: C2·C4 엔티티·서비스
  - [x] 검증 통과 (`:serverC:test --tests "*PaymentCancelFailureTest" --tests "*PaymentCancelServiceTest"` → 7개·20개 모두 통과)
- [x] 단계 2: C1 DLT 핸들러 재시도 + 최종 실패 메트릭
  - [x] 검증 통과 (`:serverC:test --tests "*KafkaConsumerConfigTest" --tests "*PaymentCancelConsumerTest"` → 3개·4개 모두 통과, 백오프 대기는 mock 컨테이너로 약 5초)
  - 참고: 재시도 중 `handleRemaining`은 패키지 전용 `RecordInRetryException`(RuntimeException)을 던진다 — 테스트는 메시지로 확인
- [x] 단계 3: P0 알람 규칙 (2개 파일)
  - [x] 검증 통과 (js-yaml 파싱 OK·두 파일 `PaymentFatalError` 규칙 동일, `helm template helm/promotion-monitoring` exit 0·렌더 결과에 새 메트릭 포함. promtool 없음 — expr 문법은 미검증)
- [x] 단계 4: 명세 갱신 (`docs/specs/user-refund.md`)
  - [x] 검증 통과 (육안 — 상태 전이 표 C4, 장애 시 동작 C1 행 추가·C2 문구, 알람 영향, 구현 단계 "진행 중", 변경 이력)
- [x] 단계 5: 전체 빌드 + 로컬 E2E 회귀
  - [x] `:serverC:build` 통과 (14개 클래스·79개 테스트, 실패·스킵 0 — Testcontainers 통합 테스트 포함)
  - [x] `backend-e2e/` `npm test` 2개 통과 (preflight 포함, 관리자 API 362ms·구매 흐름 4.8s). 스택: 멈춰 있던 mysql·mysql-c·redis·redis-b·kafka·kafka-connect를 `docker compose start`(재생성 없음), server-c만 새 jar로 `up -d --build --no-deps`

### 최종 검증
- [x] serverC 테스트 전체 통과 (79개)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (serverA·B·Grafana 미변경)
- [x] 의도하지 않은 파일 변경이 없는지 git status로 최종 확인 (`docs/batch/`는 이 작업과 무관, 미포함)
- [x] 발견 사항을 백로그로 이전 (BL-035)
- [x] (위험 변경 — 결제·이벤트) PR 생성 후 `/pr-review` 1회 — 판정 머지 가능, [필수] 0·[제안] 2(C1 P0 규칙 분리, C2 원인 예외 로그) 모두 반영. review: https://github.com/michi2012/goods-promotion-project/pull/51#pullrequestreview-5914845325 (리뷰어 약 7만 토큰·도구 9회·1분 35초). BL-033 게시 경로(리뷰어 JSON → `gh api` 게시) 정상 동작 확인

### 발견 사항 (작업 중 별도 처리 필요한 것)
- serverA·serverB `KafkaConsumerConfig`의 DLT 팩토리도 `FixedBackOff(0,0)` + 로그만 → BL-035로 이전
