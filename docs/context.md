# 맥락 노트: 사용자 환불 PR③ — cs-bot·FAQ·프론트 (BL-029 마지막 PR)

## [완료] BL-029 PR③ — BL-043 (PR #59)

### 왜 이 방식을 선택했는가
명세(`docs/specs/user-refund.md`, 설계 확인 ☑) P-1·P-11·화면 요구사항이 확정돼 있다. 아래는 구현 가정이며 사용자가 승인했다 (2026-11-16).
1. **cs-bot `requestRefund`**: 지금은 결제 내역으로 본인·PAID를 직접 확인한 뒤 `payment-cancel`을 Kafka로 직접 발행한다 → serverA를 거치지 않아 주문은 PAID로 남고 재고도 안 돌아온다. 직접 확인·발행을 지우고 `CsBotClient.requestRefund(orderId, loginId)`로 serverA 환불 API를 호출한다. 200 → 접수(REFUND_REQUESTED/REFUNDED) 안내, 4xx/5xx → serverA `ErrorResponse.message`(명세 화면 문구) 그대로 안내. 정책 판단은 serverA 한 곳(P-1).
2. **cs-bot `trackRefundStatus`**: 본인 주문 확인은 지금처럼 결제 내역으로(serverB 조회 API에는 본인 확인이 없음), 상태는 serverB 주문 상태로: REFUND_REQUESTED → 처리 중, REFUNDED → 완료, 그 외 → 현재 상태. "requestRefund 재시도" 안내는 지운다(멱등 API라 재시도가 의미 없음).
3. **Kafka 제거**: cs-bot의 Kafka 사용처는 이 직접 발행뿐 → `KafkaTemplate` 필드, `PaymentCancelMessage` DTO, `spring-kafka` 의존성, `application.yaml` kafka 설정까지 제거 (사용자 승인).
4. **FAQ**: "결제 후 7일 이내, 주문 전체 환불만"(P-3·P-4·P-11). 선착순 3일·부분 환불·미개봉 조건 삭제. 처리 기간(영업일 3~5일) 안내는 유지.
5. **프론트**: 주문 상세는 Server Component라 환불은 새 Client Component `RefundForm`(구매 `PurchaseForm`과 같은 방식, `clientA` — 브라우저는 `/api-a` 프록시). PAID일 때 로그인 ID 입력 + 버튼 + `window.confirm`(사용자 결정 — 새 컴포넌트·의존성 없음). 상태 문구: REFUND_REQUESTED "환불 처리 중", REFUNDED "환불 완료", 나머지는 원문 유지. 서버 생성 타입은 `npm run generate:api:a`로 재생성.
6. **검증**: cs-bot은 단위 테스트(LLM 키가 없어 챗봇 E2E 불가 — 사용자 결정). 프론트는 Vitest(P0 아님 — 명세 결정) + 로컬 백엔드에 붙인 실제 화면 캡처(UI 변경이라 커밋 전 사용자 확인).
7. **마지막 PR 할 일**: `refund_fatal` 설명 갱신(자동 재시도 진입 / 관리자 처리 필요는 `business_payment_cancel_retry_exhausted_total`), 명세 포트폴리오 문구를 새 기준(4개 질문 — BL-029 전체를 "분산 환경에서 환불의 이중 처리·유실·재고 불일치를 막은 문제" 한 줄, BL-034·041 포함)으로. 포트폴리오 줄은 머지 후.

### 검토했으나 채택하지 않은 대안
#### cs-bot에서 PAID·본인 여부를 먼저 확인한 뒤 serverA 호출
- 왜 안 썼나: serverA가 같은 검사를 하고 기간(7일)까지 본다. 두 곳에서 판단하면 정책이 어긋날 수 있다(P-1).
#### 확인 대화상자를 shadcn AlertDialog로
- 왜 안 썼나: `@radix-ui` 새 npm 의존성. 지금 요구는 "확인을 한 번 받는다"뿐.
#### cs-bot Kafka 의존성 유지
- 왜 안 썼나: 사용처가 없어지면 기동 시 Kafka 연결 설정만 남는다. 다시 필요해지면 그때 추가.

### 기존 코드베이스 컨벤션
- cs-bot 도구: `tools/CsBotTools.java` (`@Tool` 메서드가 문자열 안내 반환, 예외는 잡아서 안내 문구로)
- cs-bot 클라이언트: `client/CsBotClient.java` (`clientFor(서비스명)` + RestClient), 테스트 `CsBotClientTest`(MockRestServiceServer)
- 프론트 Client Component + 폼: `frontend/src/app/purchase/[goodsId]/PurchaseForm.tsx`·`PurchaseForm.test.tsx`(MSW `*/api-a/...`)
- serverA 클라이언트: `frontend/src/lib/api/client-a.ts`

### 관련 파일/위치
- `cs-bot/src/main/java/csbot/csbot/tools/CsBotTools.java` — `requestRefund`(81행), `trackRefundStatus`(148행)
- `cs-bot/src/main/java/csbot/csbot/router/CsChatAgentService.java` — 프롬프트(26·32행)
- `cs-bot/src/main/resources/faq/refund-policy.yaml`
- `frontend/src/app/orders/[orderId]/page.tsx`
- `docs/architecture/arch-snapshot.md`, `docs/architecture/flows/purchase-saga-flow.md`, `docs/design-notes.md` — `refund_fatal`

### 외부 참조
- 명세 `docs/specs/user-refund.md` — P-1·P-3·P-4·P-11, "화면", "API"(에러 코드·문구), "구현 단계", "마지막 PR에서 할 일"
- 포트폴리오 기준 `docs/portfolio.md` "문제 해결" 4개 질문
