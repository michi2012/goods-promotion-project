# 계획서: arch-snapshot 문서 정확성 수정 + Saga EXPIRED 메트릭 추가

- 작성일: 2026-06-02

## 목표
제거된 `saga:hold` 키가 arch-snapshot.md에 잘못 남아 있는 것을 정리하고, SagaTimeoutScheduler의 EXPIRED 처리 횟수를 Prometheus에서 수집할 수 있도록 Counter를 추가한다.

## 성공 기준
- [ ] arch-snapshot.md의 `saga:hold` 행이 삭제됨
- [ ] arch-snapshot.md의 `saga:state` 행 비고에 createdAt 및 3분 타임아웃 설명이 추가됨
- [ ] arch-snapshot.md mermaid 다이어그램에서 "hold TTL 3m" 표현이 제거됨
- [ ] SagaTimeoutScheduler에 `saga.expired.total` Counter가 추가되어 EXPIRED 처리 시 increment됨
- [ ] SagaTimeoutSchedulerTest에 MeterRegistry mock이 추가되어 컴파일/테스트 오류 없음

## 비범위 (Out of Scope)
- README.md: 이미 3분으로 정확히 기술되어 있으므로 변경 없음
- Prometheus 알람 규칙 파일(.yml) 추가: 메트릭 수집 기반만 마련, 알람 설정은 별도 작업
- 다른 문서의 saga:hold 언급 수정 (없음 확인됨)

## 단계별 작업 계획

### 단계 1: arch-snapshot.md 수정
- 변경 파일: `docs/arch-snapshot.md`
- 변경 내용:
  1. Redis 사용 구조 테이블: `saga:hold` 행 삭제
  2. Redis 사용 구조 테이블: `saga:state` 행 비고에 "createdAt 포함. 30초마다 SCAN, 3분 초과 시 EXPIRED 처리" 추가
  3. mermaid 다이어그램: `SagaState 초기화(Redis Hash, hold TTL 3m)` → `SagaState 초기화(Redis Hash)`
- 검증 방법: saga:hold 문자열이 파일에 없음 확인
- 예상 소요: 짧음

### 단계 2: SagaTimeoutScheduler.java 수정
- 변경 파일: `serverA/src/main/java/promotion/serverA/scheduler/SagaTimeoutScheduler.java`
- 변경 내용: `MeterRegistry` 필드 추가, EXPIRED 처리 시 `counter("saga.expired.total").increment()` 호출
- 검증 방법: 컴파일 오류 없음
- 예상 소요: 짧음

### 단계 3: SagaTimeoutSchedulerTest.java 수정
- 변경 파일: `serverA/src/test/java/promotion/serverA/scheduler/SagaTimeoutSchedulerTest.java`
- 변경 내용: `@Mock MeterRegistry meterRegistry` 추가, expired 테스트에 counter 스텁 추가
- 검증 방법: `gradlew :serverA:test --tests *SagaTimeoutSchedulerTest` 실행 요청
- 예상 소요: 짧음

### 단계 4: alert-rules.yml 추가
- 변경 파일: `monitoring/prometheus/alert-rules.yml`
- 변경 내용: Tier1-Business-Impact-SLO 그룹에 SagaExpired P1 알람 추가
  - 지표: `increase(saga_expired_total[1m]) > 0`
  - 심각도: P1 (EXPIRED 1건 = 인프라 타임아웃으로 인한 구매 실패)
- 검증 방법: yml 문법 이상 없음 확인
- 예상 소요: 짧음

## 리스크 및 대응
- `@InjectMocks`에 MeterRegistry mock 미추가 시 NPE → 단계 3에서 처리
