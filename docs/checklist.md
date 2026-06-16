# 체크리스트: aiops DLT 자동 재처리 도구

- 마지막 업데이트: 2026-06-16

## 진행 상황
- [x] 단계 1: serverA DeadLetterRepository + AdminController GET /dlt
  - [x] 검증 통과 (`.\gradlew.bat :serverA:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 2: serverA Prometheus 게이지 MetricsConfig
  - [x] 검증 통과 (`.\gradlew.bat :serverA:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 3: alert-rules.yml PurchaseDltAccumulated 추가
  - [x] 검증 통과 (파일 육안 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 4: aiops serverA RestClient + DltTools.java
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 5: aiops AiOpsAgentService DltTools 등록 + SYSTEM_PROMPT
  - [x] 검증 통과 (`.\gradlew.bat :aiops:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 6: Helm aiops 업데이트 + 테스트
  - [x] 검증 통과 (`helm template` RENDER_SUCCESS + `compileTestJava` BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과

## 최종 검증
- [ ] `.\gradlew.bat :serverA:build :aiops:build -x test` 빌드 통과
- [x] `helm template promotion-app ./helm/promotion-app` 렌더링 통과 (2026-06-16)
- [x] 변경 사항이 plan.md 비범위 침범 안 했는지 확인 (Slack 승인 미추가, Linear 이슈 미추가)
- [ ] 의도하지 않은 파일 변경 없는지 git diff 최종 확인

---

# 체크리스트: CS 자동 응대 챗봇 Phase1 — 백엔드 cs-bot 모듈

- 마지막 업데이트: 2026-06-16

## 진행 상황
- [x] 단계 1: cs-bot 모듈 골격 (settings.gradle, build.gradle, CsBotApplication, application.yaml)
  - [x] 검증 통과 (`.\gradlew.bat :cs-bot:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 2: user-service InternalUserResponse에 `Long id` 추가
  - [x] 검증 통과 (`.\gradlew.bat :user-service:compileJava :user-service:compileTestJava` → BUILD SUCCESSFUL, `:user-service:test`는 사용자 실행 필요)
  - [ ] 코드리뷰 통과
- [x] 단계 3: cs-bot 인프라 클라이언트 (RestClientConfig, CsBotClient, CsUserContext)
  - [x] 검증 통과 (`.\gradlew.bat :cs-bot:compileJava :cs-bot:compileTestJava` → BUILD SUCCESSFUL, `:cs-bot:test --tests "*CsBotClientTest"`는 사용자 실행 필요)
  - [ ] 코드리뷰 통과
- [x] 단계 4: CS 도구 (조회 통합 도구 getMyOrders/getMyProfile + 환불·취소 ACTION + 에스컬레이션)
  - [x] 검증 통과 (`.\gradlew.bat :cs-bot:compileJava :cs-bot:compileTestJava` → BUILD SUCCESSFUL, `:cs-bot:test --tests "*CsBotToolsTest"`는 사용자 실행 필요)
  - [ ] 코드리뷰 통과
- [x] 단계 5: Router/Agent + Controller (ChatMemoryConfig, CsChatAgentService, CsChatController)
  - [x] 검증 통과 (`.\gradlew.bat :cs-bot:compileJava :cs-bot:compileTestJava` → BUILD SUCCESSFUL, `:cs-bot:test --tests "*CsChatControllerTest"`는 사용자 실행 필요)
  - [ ] 코드리뷰 통과
- [x] 단계 6: gateway 라우팅 추가 (`/api/v1/cs-chat/**` → `lb://cs-bot`)
  - [x] 검증 통과 (`.\gradlew.bat :gateway-service:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 7: docker-compose 통합 + 전체 빌드/E2E 검증
  - [x] 검증 통과 (docker compose 기동 후 E2E 직접 수행 완료: `getMyOrders` 도구 호출 성공 확인)
  - [ ] 코드리뷰 통과

## 최종 검증
- [ ] 모든 단위 테스트 통과 (`.\gradlew.bat :cs-bot:test`, `.\gradlew.bat :user-service:test`)
- [ ] 전체 빌드 통과 (`.\gradlew.bat :cs-bot:build`)
- [ ] gateway에서 `/api/v1/cs-chat/**`가 JWT 없이 호출 시 401 확인
- [x] docker-compose E2E: 로그인 → JWT 획득 → `/api/v1/cs-chat/messages`로 본인 구매/결제 내역 조회 성공 (2026-06-16 확인)
- [ ] `requestRefund` 호출 시 `payment-cancel` 토픽 produce 및 serverC `PaymentCancelConsumer` 소비 확인
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (신규 serverA API 없음, 실제 PG 연동 없음, 금액/리스크 분기 없음, 프론트엔드 변경 없음, aiops 모듈 코드 변경 없음)
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

---

## [진행 중] CS 봇 도구 3종 추가 (Phase1 보완)

- 마지막 업데이트: 2026-06-16

## 진행 상황
- [x] 단계 1: serverA API 추가 (상품 조회 + DLT 조회)
  - [x] GoodsResponse, DltResponse DTO 신규 생성
  - [x] GoodsService.findById, DeadLetterRepository.findByOrderId 추가
  - [x] GoodsController GET /api/v1/goods/{goodsId}, AdminController GET /api/v1/admin/dlt/orders/{orderId} 추가
  - [x] 검증 통과 (`.\gradlew.bat :serverA:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 2: serverC PaymentCancelConsumer CANCELLED 업데이트
  - [x] PaymentRepository 주입, pgClient.cancelPayments() 후 updateOrderStatus("CANCELLED") 추가
  - [x] 검증 통과 (`.\gradlew.bat :serverC:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 3: cs-bot DTO 추가 + CsBotClient 확장
  - [x] GoodsResponse, OrderStatusResponse, DltResponse DTO 신규 생성
  - [x] CsBotClient에 getGoodsInfo, getOrderStatus, getDltByOrderId, retryDlt 메서드 추가
  - [x] 검증 통과 (`.\gradlew.bat :cs-bot:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 4: CsBotTools 도구 4개 + SYSTEM_PROMPT
  - [x] getOrderDetail, trackRefundStatus, diagnosePaymentIssue, checkAndRetryPurchaseDlt 구현
  - [x] SYSTEM_PROMPT에 시나리오별 처리 지침 추가 (환불 재처리 안내 포함)
  - [x] 검증 통과 (`.\gradlew.bat :cs-bot:compileJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과
- [x] 단계 5: 테스트 코드
  - [x] CsBotToolsTest에 신규 도구 8개 케이스 추가
  - [x] serverA GoodsControllerTest 신규 생성 (성공/404)
  - [x] 검증 통과 (`.\gradlew.bat :cs-bot:compileTestJava :serverA:compileTestJava` → BUILD SUCCESSFUL)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] 전체 빌드 통과 (`.\gradlew.bat :cs-bot:build :serverA:build :serverC:build -x test` → BUILD SUCCESSFUL)
- [ ] 단위 테스트 통과 (`.\gradlew.bat :cs-bot:test :serverA:test`) — 사용자 직접 실행
- [ ] 변경 사항이 plan.md 비범위 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경 없는지 git diff 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- serverA, serverB 로컬 E2E를 위해 `docker compose up -d server-a server-b redis-b` 추가 기동 필요

---

## 발견 사항 (작업 중 별도 처리 필요한 것)
- 단계 4: `Payment.status`의 실제 값은 `PAID`/`FAILED`뿐(`SUCCESS` 없음). `requestRefund`의 취소 가능 상태 가드를 plan.md의 `SUCCESS` 대신 `PAID`로 구현(`CANCELABLE_STATUS = "PAID"`). 또한 `MockPgClient.cancelPayments()`가 취소 후 DB status를 변경하지 않아, 동일 `orderId`에 대한 중복 취소 요청 자체는 차단되지 않음(본인 소유 + 현재 PAID 사전조건 체크로 한정) — 기존 시스템의 한계이며 Phase1 비범위로 유지(사용자 확인 완료).

---

## [진행 중] cs-bot Helm 차트 추가

- 마지막 업데이트: 2026-06-16

## 진행 상황
- [x] 단계 1: values.yaml csBot 섹션 추가
  - [x] 검증 통과 (파일 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 2: deployment.yaml + service.yaml 생성
  - [x] 검증 통과 (파일 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 3: virtualservice.yaml + destinationrule.yaml 생성
  - [x] 검증 통과 (파일 확인)
  - [ ] 코드리뷰 통과
- [x] 단계 4: rbac.yaml 생성 + helm template 렌더링 검증
  - [x] 검증 통과 (`helm template promotion-app ./helm/promotion-app` → RENDER_SUCCESS)
  - [ ] 코드리뷰 통과

## 최종 검증
- [x] `helm template promotion-app ./helm/promotion-app` 오류 없이 렌더링 (2026-06-16)
- [x] 변경 사항이 plan.md 비범위 침범 안 했는지 확인 (Ingress 미변경, HPA 미추가)
- [ ] 의도하지 않은 파일 변경 없는지 git diff 최종 확인
