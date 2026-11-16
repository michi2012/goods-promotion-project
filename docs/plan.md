# 계획서: 사용자 환불 PR③ — cs-bot·FAQ·프론트가 serverA 환불 API를 쓴다 (BL-029 마지막 PR)

- 작성일: 2026-11-16
- 관련 이슈/티켓: BL-043 (BL-029의 하위 항목, `docs/backlog.md`), 명세 `docs/specs/user-refund.md` (설계 확인 ☑)
- 새 규칙 적용 (PR #58 — PR을 나누면 백로그 하위 항목, 상태는 백로그에만): 이 PR부터 BL-029 PR③을 BL-043으로 관리하고, 명세 "구현 단계" 표의 상태 칸을 백로그/PR 칸으로 바꾼다

## [완료] BL-029 PR③ — BL-043 (PR #59)

### 목표
환불 요청 경로를 serverA 환불 API 하나로 모은다(P-1): cs-bot의 `payment-cancel` 직접 발행을 없애고 serverA API를 호출하게 하고, 환불 상태는 serverB 주문 상태로 답한다. FAQ를 정책(결제 후 7일·주문 전체만)에 맞추고, 주문 상세 화면에 환불 버튼·상태 문구를 추가한다. BL-029 마지막 PR이라 아키텍처 문서의 `refund_fatal` 설명을 갱신한다.

### 성공 기준
- [ ] cs-bot `requestRefund`가 serverA `POST /api/v1/orders/{orderId}/refund`(`X-User-Id`=로그인 ID)를 호출하고, 200은 접수 안내·4xx/5xx는 serverA 문구를 그대로 안내한다. cs-bot에 Kafka 코드·의존성·설정이 없다 (`CsBotToolsTest`, `CsBotClientTest`, `:cs-bot:build`)
- [ ] cs-bot `trackRefundStatus`가 본인 주문 확인 후 serverB 주문 상태로 "환불 처리 중 / 환불 완료"를 답한다 (`CsBotToolsTest`)
- [ ] FAQ(`refund-policy.yaml`)가 "결제 후 7일, 주문 전체 환불만"으로 안내하고 선착순 3일·부분 환불·미개봉 조건이 없다
- [ ] 주문 상세 화면: PAID면 로그인 ID 입력 + 환불 버튼 + 확인(`window.confirm`) 후 요청, 성공 시 "환불 처리 중", 거절 시 serverA 문구. 상태 REFUND_REQUESTED/REFUNDED는 "환불 처리 중"/"환불 완료"로 표시 (Vitest + RTL + MSW, `npm run test`·tsc·lint)
- [ ] 로컬 백엔드에 붙인 실제 화면으로 PAID → 환불 → 처리 중 → 완료 흐름을 캡처해 사용자 확인
- [ ] arch-snapshot·`flows/purchase-saga-flow.md`·design-notes의 `refund_fatal` 설명 갱신, 명세 구현 단계 ③·포트폴리오 문구(BL-029 전체를 한 줄) 갱신

### 비범위 (Out of Scope)
- 챗봇 실제 대화 E2E (LLM API 키 없음 — 사용자 결정: 단위 테스트로 충분)
- 프론트 UI E2E·시각 회귀 (명세 결정: P0 아님 → 단위 테스트만)
- 프론트 로그인 (명세 범위 밖 — 로그인 ID 직접 입력)
- docker-compose·Helm의 cs-bot Kafka 환경변수 정리 (남아도 무해 — 발견 사항으로 기록)
- BL-038(환불 완료 실패 알람)·BL-042(1인 1회 유효 기간)

### 단계별 작업 계획

#### 단계 1: cs-bot — serverA 환불 API 호출 + 상태는 serverB + Kafka 제거 — 🔶 의존성 제거, 단계별 승인
- 변경 파일: `cs-bot/.../client/CsBotClient.java`(`requestRefund`), `client/dto/RefundResponse.java`(신규), `client/dto/PaymentCancelMessage.java`(삭제), `tools/CsBotTools.java`, `router/CsChatAgentService.java`(프롬프트), `cs-bot/build.gradle`(spring-kafka 제거), `src/main/resources/application.yaml`(kafka 설정 제거), 테스트 `CsBotToolsTest`, `CsBotClientTest`
- 변경 내용: 가정 1~3 (context.md). 정책 판단은 serverA에만 둔다.
- 검증: `.\gradlew.bat :cs-bot:test --tests "*CsBotToolsTest" --tests "*CsBotClientTest"` → `:cs-bot:build`
- 롤백: `git checkout -- <파일>`, 삭제 파일 복원
- 예상 소요: 보통

#### 단계 2: FAQ 정책 맞춤
- 변경 파일: `cs-bot/src/main/resources/faq/refund-policy.yaml`
- 검증: 육안 + `:cs-bot:test` (FAQ 로딩 테스트가 있으면 함께)
- 예상 소요: 짧음

#### 단계 3: 프론트 — 환불 버튼·상태 문구
- 변경 파일: `frontend/src/api/generated/server-a-types.ts`(재생성), `frontend/src/app/orders/[orderId]/RefundForm.tsx`(신규), `RefundForm.test.tsx`(신규), `page.tsx`(상태 문구 + PAID일 때 RefundForm)
- 변경 내용: 가정 5. `clientA`(브라우저는 `/api-a` 프록시)로 호출. `frontend` skill 규칙 따름.
- 검증: `npm run test` · `npx tsc --noEmit` · `npm run lint`
- 롤백: `git checkout -- <파일>`, 신규 삭제
- 예상 소요: 보통

#### 단계 4: 화면 확인 (로컬 백엔드)
- 검증: `npm run dev` + 로컬 백엔드(서버 기동 상태 유지) → 구매로 PAID 주문 생성 → 주문 상세에서 환불 → "환불 처리 중" → 새로고침 후 "환불 완료"를 Playwright 스크린샷으로 캡처해 사용자에게 보여줌
- 예상 소요: 보통

#### 단계 5: 문서 (마지막 PR에서 할 일)
- 변경 파일: `docs/architecture/arch-snapshot.md`, `docs/architecture/flows/purchase-saga-flow.md`, `docs/design-notes.md`(`refund_fatal` = 자동 재시도 진입, 관리자 처리 필요 = `business_payment_cancel_retry_exhausted_total`), `docs/specs/user-refund.md`("구현 단계" 표: 상태 칸 → 백로그/PR 칸(①#48·BL-034 #51·②-a #52·②-b #55·BL-041 #56·③ BL-043), 포트폴리오 문구), `docs/backlog.md`(BL-043 진행 중 — 등록 완료)
- 검증: 육안 (키워드 grep으로 옛 설명이 남지 않았는지)
- 예상 소요: 보통

### 리스크 및 대응
- 리스크: Spring Kafka 자동 설정이 의존성 제거 후 다른 빈과 엮여 기동 실패 → 대응: `:cs-bot:build`(컨텍스트 로드 테스트 포함)로 확인
- 리스크: cs-bot이 serverA 4xx를 예외로 받아 "오류 발생"으로 뭉뚱그림 → 대응: `RestClient` 4xx 응답 본문(`ErrorResponse.message`)을 꺼내 그대로 안내, `CsBotClientTest`로 404·409 고정
- 리스크: 브라우저 `window.confirm`은 테스트 환경에서 막힘 → 대응: `vi.spyOn(window, 'confirm')`
- 리스크: 화면 확인 중 모의 PG 거절(1%)로 PAID가 안 됨 → 대응: 구매를 다시 해서 새 주문으로 진행

### 의존성
- 의존성 **제거**: cs-bot `spring-kafka` (사용자 승인 2026-11-16). 추가 없음
- serverA 환불 API(#55)·serverB 상태 enum(#52) 머지됨
