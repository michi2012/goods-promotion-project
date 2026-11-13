# 체크리스트: main CI 정상화

- 마지막 업데이트: 2026-11-13

## [진행 중] serverB 계약 테스트 + 배포 스위치

### 진행 상황
- [x] 단계 1: serverB `springdoc.default-produces-media-type: application/json`
  - [x] 계획 대비 추가 (사용자 승인): `*/*` 해결 후 실제 계약 불일치 발견 — 계약 `status` enum vs 구현 `String`. `OrderStatusResponse.status`에 `@Schema(allowableValues = PENDING/PAID/FAILED/EXPIRED/NOT_FOUND)` (실행 동작 무변경, #15부터 존재하던 drift)
  - [x] 검증 통과 (CI H2 환경변수로 `:serverB:test` 15/15)
- [x] 단계 2: `ci-cd.yaml` `set-environment`에 `vars.DEPLOY_ENABLED == 'true'` 조건 ⚠️ 인프라 변경
  - [x] 검증 통과 (Node YAML 파싱 OK, image-build·deploy는 needs로, create-pr은 암묵 success()로 연쇄 skipped)

### 최종 검증
- [ ] 이 PR의 CI `test` job 초록색 (serverB 이후 모듈까지 실행)
- [ ] 머지 시 배포 job들이 skipped (머지 후 Actions 확인)
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 이후 작업
- [ ] 작업 A: E2E 규칙 정비 (`chore/e2e-guidelines`, 계획 승인 완료 — 계획 문서 임시 보관 중)
- [ ] 작업 B (`/plan`): Playwright API E2E + 사전 점검 + 구매 UI PAID 확인 + 프론트 CI + H2 환경변수 정리

## 발견 사항 (작업 중 별도 처리 필요한 것)
- 
