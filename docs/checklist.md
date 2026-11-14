# 체크리스트: 사용자 환불 + 환불 실패 재처리 (BL-029)

- 마지막 업데이트: 2026-11-14 (단계 1~7 완료)

## [완료] PR① serverC — PG 취소 멱등화 + 실패 재처리 (PR #48)

### 진행 상황
- [x] 단계 1: 실패 기록 테이블 + 엔티티
  - [x] 검증 통과 (`gradlew :serverC:test --tests "*PaymentCancelFailure*"` — 엔티티 7, 리포지토리 5 통과)
  - [ ] 코드리뷰 통과 (미실시 — 리뷰 에이전트 연결 문제로 사용자 결정에 따라 생략, 2026-11-14)
- [x] 단계 2: PG 취소 멱등화
  - [x] 검증 통과 (`--tests "*MockPgClientTest" --tests "*PaymentServiceTest"` — 3, 13 통과)
  - [ ] 코드리뷰 통과 (미실시 — 리뷰 에이전트 연결 문제로 사용자 결정에 따라 생략, 2026-11-14)
- [x] 단계 3: 취소 처리 서비스 + 컨슈머 + `payment-cancelled` 발행
  - [x] 검증 통과 (`--tests "*PaymentCancelServiceTest" --tests "*PaymentCancelConsumerTest"` — 7, 2 통과)
  - [ ] 코드리뷰 통과 (미실시 — 리뷰 에이전트 연결 문제로 사용자 결정에 따라 생략, 2026-11-14)
- [x] 단계 4: DLT 기록 + 자동 재시도 스케줄러
  - [x] 검증 통과 (`--tests "*PaymentCancelServiceTest" --tests "*PaymentCancelConsumerTest" --tests "*PaymentCancelRetry*"` — 13, 3, 1, 통합 3 통과)
  - [ ] 코드리뷰 통과 (미실시 — 리뷰 에이전트 연결 문제로 사용자 결정에 따라 생략, 2026-11-14)
- [x] 단계 5: 관리자 API
  - [x] 검증 통과 (`--tests "*PaymentAdminControllerTest" --tests "*PaymentCancelServiceTest" --tests "*PaymentControllerTest"` — 7, 19, 4 통과)
  - [ ] 코드리뷰 통과 (미실시 — 리뷰 에이전트 연결 문제로 사용자 결정에 따라 생략, 2026-11-14)
- [x] 단계 6: 알람 규칙
  - [x] 검증 통과 (`helm template` promotion-monitoring — 렌더링 성공, 새 expr 반영 확인)
- [x] 단계 7: 전체 검증
  - [x] `gradlew :serverC:test` 통과 (13 클래스 74 테스트)
  - [x] 서버 기동 전 사용자 확인 (serverC V3 마이그레이션 — 로그상 v3 적용 확인)
  - [x] 백엔드 E2E 회귀 통과 (`backend-e2e npm test` 2 passed — 구매 흐름 + 관리자 API)
  - [x] 관리자 API 확인 — 수동 호출 대신 E2E 시나리오 2(`payment-cancel-admin.spec.ts`)로 고정

### 최종 검증
- [ ] 모든 단위 테스트 통과
- [ ] 모든 통합 테스트 통과
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [x] 사용자에게 데이터 전후를 보여주고 확인받은 뒤 커밋 (e2e skill 7항 — 취소 동작 시연 + E2E 리포트 http://localhost:9323)
- [x] 발견 사항을 백로그로 이전 (BL-031, 문서 3곳은 plan.md)

### 발견 사항 (작업 중 별도 처리 필요한 것)
- ~~`refund_fatal` 의미 변경 설명 문서 3곳~~ → `plan.md` "BL-029 마지막 PR에서 할 일"로 이전
- ~~Grafana "4. [P0] 자동 복구 실패" 패널 제목~~ → 백로그 BL-031로 이전
