# 체크리스트: PG 대사 PR① — 대사 기반 데이터 (BL-045)

- 마지막 업데이트: 2026-11-17

## [진행 중] BL-002 PR① — BL-045

### 진행 상황
- [x] 단계 1: 마이그레이션 V4·V5
  - [x] 기존 `PaymentRepositoryTest`(Flyway 적용) 통과 — 새 컬럼 사용 검증은 단계 2
- [x] 단계 2: `PaymentRepository` 시각 기록 메서드
  - [x] 검증 통과 (`--tests "*PaymentRepositoryTest"` 10건)
- [x] 단계 3: 서비스에서 시각 기록
  - [x] 검증 통과 (13·20·3건) (`*PaymentServiceTest`, `*PaymentCancelServiceTest`, `*PaymentCancelRetryIntegrationTest`)
- [x] 단계 4: 모의 PG 원장
  - [x] 검증 통과 (`*MockPgLedgerTest` 4건, `*MockPgClientTest` 7건)
- [x] 단계 5: CSV 파일 생성기
  - [x] 검증 통과 (`*MockPgFileExporterTest` 4건 — 1차 실패는 테스트의 Mockito 매처 혼용, 1회 수정으로 통과)
- [x] 단계 6: 전체 테스트 + 로컬 E2E
  - [x] `.\gradlew.bat :serverC:test` (16 스위트 96건, 실패 0)
  - [x] E2E (V4·V5 적용 사용자 확인 후) — backend-e2e 3/3 통과, `payments` 시각·원장 3행·CSV(교체 포함) 확인

### 최종 검증
- [ ] 모든 단위 테스트 통과
- [ ] 모든 통합 테스트 통과
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [ ] 사용자에게 데이터 전후(시각 컬럼·원장·CSV)를 보여주고 확인받은 뒤 커밋 (e2e skill 7항)
- [ ] (위험 변경: 결제·마이그레이션) PR 생성 후 `/pr-review` 1회 — [필수] 반영·재리뷰
- [ ] 포트폴리오 후보 판단 — 아니오 예상: 이 PR은 기반 데이터라 해결한 문제가 없음. 정합성 3종은 BL-046 이후 한 줄로

### 발견 사항
- (없음)
