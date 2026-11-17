# 체크리스트: PG 대사 PR②-b — 대사 운영 연결 (BL-051)

## [진행 중] BL-002 PR②-b — BL-051

- 마지막 업데이트: 2026-11-17 (단계 1~4 완료)

## 진행 상황
- [x] 단계 1: 메타 조회 Repository + 실행 서비스 + 400/409 예외
  - [x] 검증 통과 (`--tests "*ReconciliationRun*"`: Repository 3 + Service 9)
- [x] 단계 2: 관리자 API
  - [x] 검증 통과 (`--tests "*ReconciliationAdminControllerTest"`: 5)
- [x] 단계 3: 메트릭 리스너 + 기동 시 정리
  - [x] 검증 통과 (Metrics 4 + Cleaner 2, 기존 Job·재현 테스트 모두 통과)
- [x] 단계 4: 03:00 스케줄러
  - [x] 검증 통과 (`--tests "*ReconciliationSchedulerTest"`: 2)
- [x] 단계 5: 알람 2벌 (인프라 — 단계별 승인)
  - [x] promtool check rules(prom/prometheus:v2.45.0: monitoring 30, helm 39 rules SUCCESS), `helm template` 통과
- [x] 단계 6: 백엔드 E2E
  - [x] serverC 재빌드(bootJar → compose up --build server-c) 후 `npx playwright test reconciliation-admin payment-cancel-admin` 2 passed. 실패 메트릭 0 → 2(첫 실행), 재시작 시 clear Step이 다시 돌지 않음(로그)
- [x] 단계 7: 명세 변경 이력·문서 (게이지 기준, 400 기준·스케줄 건너뜀·비동기)

## 최종 검증
- [x] serverC 테스트 통과 (`.\gradlew.bat :serverC:test` — 150 tests, 실패 0)
- [x] 변경 사항이 plan.md "비범위"를 침범하지 않았는지 확인 (조회 API·다중 파드·성공 경로 E2E 없음)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (변경 12 + 신규 16 = 28개, 모두 계획 범위)
- [x] 사용자에게 E2E 결과(리포트 URL + 메트릭 전후)를 보여주고 확인받은 뒤 커밋
- [x] 실제 서버 성공 경로 데모 (사용자 요청): 2026-11-10, 우리 8건 + CSV 9줄 → 4유형 각 1건, 정상·경계 제외, 재실행 시 교체. 결과는 명세 3-1절
- [ ] (위험 변경: 배치 재시작·운영 알람) PR 생성 후 `/pr-review` 1회 — [필수] 반영·재리뷰
- [ ] 포트폴리오 후보 판단 — 아니오 예상: 운영 연결이라 정합성 문제 해결은 PR②-a에 있다. 정합성 3종은 BL-047 마지막 PR에서 한 줄로 정리

## 발견 사항 (작업 중 별도 처리 필요한 것)
- [이 PR에 포함] serverC `GlobalExceptionHandler`: 쿼리·경로 파라미터 누락·형식 오류가 `Exception` 핸들러에서 500이 되던 것을 400으로 바꿈 (새 API의 `date` 검증에 필요). 부수 효과로 기존 `GET /api/v1/payments/users/{userId}?page=abc` 같은 형식 오류도 500 → 400
- [이 PR에 포함] 데모에서 발견: 같은 날짜를 다시 실행하면 불일치 카운터가 다시 더해짐(명세대로 "대사 1회마다 증가"). `ReconciliationMismatch` 알람 설명에 "실제 건수는 테이블 기준" 추가
- 원장 시각이 결제 시각보다 하루 앞선 기존 데이터 2건(2026-11-17 결제) — 코드상 같은 시계라 이전 수동 검증의 흔적으로 보임. 사용자 판단: 조사 불필요
