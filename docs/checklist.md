# 체크리스트: arch-snapshot 문서 정확성 수정 + Saga EXPIRED 메트릭 추가

- 마지막 업데이트: 2026-06-02

## 진행 상황
- [x] 단계 1: arch-snapshot.md 수정
  - [x] saga:hold 행 삭제
  - [x] saga:state 행 비고 업데이트
  - [x] mermaid 다이어그램 "hold TTL 3m" 제거
  - [x] 파일 내 saga:hold 문자열 없음 확인
- [x] 단계 2: SagaTimeoutScheduler.java 수정
  - [x] MeterRegistry 필드 추가
  - [x] counter("saga.expired.total").increment() 추가
- [x] 단계 3: SagaTimeoutSchedulerTest.java 수정
  - [x] @Mock MeterRegistry 추가
  - [x] counter 스텁 추가
  - [ ] 검증: gradlew :serverA:test --tests *SagaTimeoutSchedulerTest
- [x] 단계 4: alert-rules.yml 추가
  - [x] Tier1에 SagaExpired P1 알람 추가

## 최종 검증
- [x] 변경 사항이 plan.md의 비범위를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항
- README.md는 이미 정확 (수정 불필요)
