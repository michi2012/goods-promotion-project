# 체크리스트: PG 대사 PR②-a — 대사 Job 본체 (BL-050)

- 마지막 업데이트: 2026-11-17

## [완료] BL-002 PR②-a — BL-050 (PR #63)

## 진행 상황
- [x] 단계 1: 의존성·설정·V6·V7 (spring-batch-core 5.2.5)
  - [x] 검증 통과 (`compileJava`, `PaymentRepositoryTest` 10/10 — Flyway 스키마 사용)
- [x] 단계 2: 대사 저장소
  - [x] 검증 통과 (Payment 11, PgSettlementLine 3, ReconciliationMismatch 2 — 0 실패)
- [x] 단계 3: Step 1 clear·Step 2 load — 계획과 차이: 별도 reader 클래스 대신 Step 2를 `reconLoadPrevTailStep`(D-1, strict=false + 꼬리 필터)·`reconLoadDayStep`(D, strict)으로 분리
  - [x] 검증 통과 (`ReconciliationJobTest` 적재·S-10·D-1 없음)
- [x] 단계 4: Step 3a·3b·4·5 — 계획과 차이: 판정 로직을 순수 클래스 `ReconciliationMatcher`로 분리(단위 테스트 8), processor는 설정 안 람다
  - [x] 검증 통과 (`ReconciliationJobTest` 6/6, `ReconciliationMatcherTest` 8/8)
- [x] 단계 5: 재현 테스트 3종
  - [x] 검증 통과 (Cutoff 2, Restart 2, Paging 2 — 각각 단순 구현 실패 재현 + Job 통과)
  - 함정 기록: `@SpringBatchTest` 오작동 → `.claude/skills/testing/project.md`
- [x] 단계 6: 전체 검증·문서
  - [x] `gradlew :serverC:test` 통과 (123 tests, 0 실패)
  - [x] 로컬 serverC 기동(사용자 확인 후) — V6·V7 적용(flyway v7), `/actuator/health` UP, 기동 시 Job 자동 실행 0회

## 최종 검증
- [x] 모든 단위 테스트 통과
- [x] 모든 통합 테스트 통과 (Testcontainers)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (API·스케줄·메트릭·알람 없음)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [x] E2E(API 호출)는 진입점이 생기는 BL-051에서 — 이 PR은 Job 테스트 + 서버 기동 확인 (사용자 합의)
- [ ] (위험 변경: Batch 도입·마이그레이션·정합성) PR 생성 후 `/pr-review` 1회 — [필수] 반영·재리뷰
- [ ] 포트폴리오 후보 판단 — BL-002 묶음의 중간 PR이라 기록은 마지막 PR(BL-047) 후 (명세 "마지막 PR에서 할 일")

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (없음)
