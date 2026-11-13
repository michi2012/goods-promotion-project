# 체크리스트: E2E 스크립트 고정 + PR CI 개선 (BL-001)

- 마지막 업데이트: 2026-11-13

## [진행 중] BL-001 (+ BL-011)

### 진행 상황
- [x] 단계 1: serverC `PaymentResponse`에 `amount`·`currency`
  - [x] 검증 통과 (PaymentServiceTest 13, PaymentRepositoryTest 6, PaymentControllerTest 4)
- [x] 단계 2: 사전 점검(`preflight.ts`) + API E2E(`e2e/api/purchase-flow.spec.ts`)
  - [x] 검증 통과 (API E2E 통과, serverB 주소를 :9999로 바꾸면 serverB만 명확히 실패)
  - [x] 보강: 첫 실행에서 serverA health가 일시적으로 5초 초과(직후 측정 19~122ms) → 타임아웃 10초 + 1회 재확인
  - [x] 계획 변경 (사용자 결정, 팀 분리 가정): 백엔드 E2E를 독립 폴더 `backend-e2e/`로 이동 — package.json·lock·playwright.config.ts·preflight.ts·purchase-flow.spec.ts(①~④ test.step + 응답 첨부 + trace)·SCENARIOS.md·.gitignore. `frontend/e2e/api/`·`playwright.api.config.ts`·`test:e2e:api` 삭제
  - [x] 검증 통과 (`cd backend-e2e && npm test` 1/1, 7초, 3000 포트 미사용 — 프론트·브라우저 불필요 / 프론트 `playwright test` 7/7, eslint 0건 / `git add -n`으로 node_modules·리포트 제외 확인)
- [x] 단계 3: UI E2E 구매 → PAID까지
  - [x] 검증 통과 (`playwright test` 전체 8/8)
- [x] 단계 4: orders 페이지 lint 3건 (BL-011)
  - [x] 검증 통과 (`eslint` 0건, `tsc` 통과, Vitest 15/15)
- [x] 단계 5: backend-ci / frontend-ci 분리, ci-cd.yaml `test` 제거 ⚠️ 인프라 (사용자 승인)
  - [x] 검증 (YAML 파싱 3개 OK, backend 매트릭스 9개 = settings.gradle 9개, fail-fast=false, ci-cd 트리거 closed만, 미사용 환경변수는 코드·설정 어디에도 참조 없음 확인)
  - [ ] PR Actions 결과 (PR 생성 후)
- [x] 단계 6: project.md(두 층·상황별 실행·CI 실제 구성) · CLAUDE.md(테스트 분업 — 프론트 CI 반영, E2E는 CI 밖) · design-notes(E2E 두 층과 도구·전환 조건) · backlog(BL-019~022 추가, BL-007·018 갱신)
- [x] 단계 7: 로컬 E2E 최종 검증 + 근거 보고 — 백엔드 E2E 1/1(①~④ 응답 첨부·trace, 리포트 브라우저 공개), UI E2E 7/7(30,000원·PAID 확인 시점 스크린샷), 사용자 확인

### 최종 검증
- [x] 수정한 백엔드 테스트 통과, 프론트 Vitest·lint·build 통과
- [x] 로컬 E2E(백엔드 + UI) 통과, 근거 보고 완료
- [ ] PR CI 두 워크플로 초록색 (PR 생성 후)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인
- [x] 발견 사항을 `docs/backlog.md`로 이전

## 발견 사항 (작업 중 별도 처리 필요한 것)
- (해결) 단계 2~4 보고에서 E2E 근거(스크린샷·데이터)를 빠뜨림 → `E2E_SCREENSHOTS=1` 옵션 + 확인 시점 스크린샷·응답 JSON을 리포트에 첨부하도록 스펙 보강
- (해결) Playwright 자동 스크린샷(test-finished)은 테스트 종료 후 재렌더 시점과 겹쳐 "조회 중…"이 찍힐 수 있음 → 근거 스크린샷은 확인 직후 `test.info().attach`로 직접 첨부
- (해결) API E2E를 `frontend/` 밖 독립 폴더로 분리 → `backend-e2e/`로 이번 작업에서 처리
- 백로그로 이전: 필수 체크 도입 시 경로 필터 skip 처리 → BL-019 / 수동·공유용 API 도구(Postman 또는 `.http`) → BL-020 / REST Assured 조건부 전환 → BL-021 / 프론트 단독 mock UI E2E → BL-022
