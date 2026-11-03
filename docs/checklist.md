# 체크리스트: serverC PG 거래 조회 API + 멱등 키 기반 재시도 방어 (가정 구현)

- 마지막 업데이트: 2026-11-03 00:30

## 진행 상황
- [x] 단계 1: `PgTransactionStatus` enum + `PgAmbiguousResponseException` 추가
  - [x] 검증 통과 (`gradlew :serverC:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 2: `PgClient`에 `queryTransaction` 시그니처 추가
  - [x] 검증 통과 (`gradlew :serverC:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 3: `MockPgClient` 내부 상태 저장 + 조회/멱등 로직 구현 (+ 계획 외: `application.yaml`에 `ignoreExceptions` 추가)
  - [x] 검증 통과 (`gradlew :serverC:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 4: `PaymentService.processPayment` 조회+재시도 분기 추가 (계획보다 단순화 — 기존 catch 블록 재사용)
  - [x] 검증 통과 (`gradlew :serverC:compileJava`)
  - [ ] 코드리뷰 통과
- [x] 단계 5: 테스트 작성(신규 3개) 및 기존 테스트 무변경 통과 확인
  - [x] 검증 통과 (`gradlew :serverC:test --tests "*PaymentServiceTest*"` — 9 tests, 0 failures)
  - [ ] 코드리뷰 통과
- [x] 단계 6: `docs/context.md` 마무리 + 전체 검증
  - [x] 검증 통과 (`gradlew :serverC:test` — 우리 변경과 무관한 `PaymentRepositoryTest`(Testcontainers/Docker 필요) 1건만 인프라 미기동으로 실패, `PaymentServiceTest` 9/9 통과)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] 모든 단위 테스트 통과 (`PaymentServiceTest` 9/9)
- [x] 통합 테스트: `PaymentRepositoryTest`는 Docker/Testcontainers 필요 — 로컬에 Docker 미기동이라 Claude가 직접 실행 못 함(기존부터 그런 테스트, 이번 변경과 무관)
- [ ] 린터 통과 (해당 시 — 별도 린터 설정 없음)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (TimeLimiter 미도입, DB 마이그레이션 없음, 다른 서버/프론트 무변경 — `git diff --stat`으로 확인)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (`docs/*` 3개 + `serverC` 6개, 계획대로)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- `@CircuitBreaker`의 `fallbackMethod(Throwable)`가 모든 예외를 가로채 `PgPaymentException`으로 바꿔버리는 걸 발견 — `application.yaml`에 `ignoreExceptions` 추가로 해결(사용자 승인 받음). 단위 테스트(Mockito mock)로는 이 문제가 드러나지 않는다는 점도 `docs/context.md`에 기록 필요.
