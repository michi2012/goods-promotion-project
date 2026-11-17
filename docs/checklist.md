# 체크리스트: 대사 배치 성능 개선 (BL-008)

## [진행 중] BL-008

- 마지막 업데이트: 2026-11-17

## 진행 상황
- [x] 단계 1: 일괄 조회 메서드 (`PgSettlementLineRepository.findAll`, `PaymentRepository.findEventTimes(Collection)`, 단건 메서드 제거)
  - [x] 저장소 테스트 통과 (PaymentRepositoryTest 11, PgSettlementLineRepositoryTest 5)
- [x] 단계 2: 비교 Step 3개 → writer에서 chunk 일괄 판정 (1·2 묶음 실행 — 1만 바꾸면 컴파일 불가)
  - [x] `promotion.serverC.batch.*` 테스트 통과 30건 (재현 3종 포함). 1차 실패: `ReconciliationPagingReproTest` 스파이가 `find` 참조 → `findAll`로 (chunk 2라 끼워 넣는 시점 동일)
- [x] 단계 3: 100만 전후 측정 — 실행 42·43·44: 316.7/310.7/302.1초(중앙값 310.7, 기준선 713.8 대비 −56%), 비교 Step 268→74·267→71초, 건수 매회 일치. 실행 41은 시드 적재 중 시작돼 제외 (TaskStop 후 스크립트가 살아 두 벌 실행 → 정리, CLAUDE.local.md 기록)
- [x] 단계 4: 생략 (사용자 결정) — 500 유지, 결과 문서 "하지 않은 것"에 기록
- [x] 단계 5: `docs/perf/2026-11-17-pg-reconciliation-batch-lookup.md`, 명세 변경 이력, arch-snapshot, 백로그(BL-052 종료, BL-054 조건부)

## 최종 검증
- [x] serverC 전체 테스트 통과 (gradlew :serverC:test — 152건, 실패 0)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [x] 측정 후 로컬 시드 정리(seed 0행, payments 26행), serverC 원래 설정 확인(chunk 기본 500)
- [ ] 사용자에게 전후 수치 보여주고 확인받은 뒤 커밋
- [ ] (위험 변경 — 대사 정확성) PR 생성 후 `/pr-review` 1회 — [필수] 반영·재리뷰
- [x] 포트폴리오 후보 판단 — 예 (측정으로 병목 확인·원인 분석·대안 비교(chunk만 vs 일괄 조회)·전후 실측). 머지 후 새 사례로 제안

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (없음 — BL-054는 /plan에서 바로 백로그로 분리)
