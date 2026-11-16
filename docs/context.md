# 맥락 노트: 사용자 환불 PR②-a — serverB 상태 enum + 게이트웨이 환불 라우트 (BL-029)

## [완료] BL-029 PR②-a (PR #52)

### 왜 이 방식을 선택했는가
- 명세(`docs/specs/user-refund.md`, 설계 확인 ☑)의 PR②는 serverA·게이트웨이·serverB 계약·E2E를 한 PR로 잡았지만, 예상 변경 파일이 약 30개라 둘로 나눴다 (사용자 결정 2026-11-16).
  - ②-a: serverB enum + `contracts/openapi.yaml` + 게이트웨이 라우트 (명세 배포 순서상 serverB가 먼저)
  - ②-b: serverA 환불 API·컨슈머·V4·user-service 조회 + 환불 E2E
- 환불 E2E의 "중복 요청에도 PG 취소 1회"는 재고가 정확히 한 번 복구됐는지로 확인한다 (사용자 결정). PG 멱등 자체는 serverC 단위 테스트(PR①). → ②-b에서 적용

### PR②-b로 넘기는 확인 사실 (Step 1 조사)
- PAID 전이는 `Order.markAsPaid()`가 아니라 `OrderRepository.updateStatusIfPending(orderId, PAID)`(JPQL 벌크) — 호출부 `SagaOrchestratorService.tryCompleteSaga`·`reconcileToPaid`. `paid_at`은 이 UPDATE에서 함께 기록해야 한다 (명세의 "markAsPaid에서 기록"은 구현 위치만 다름)
- serverA 예외: `BusinessException`은 항상 400, `PromotionException`은 에러 코드가 HTTP 상태명 → 명세의 404 `ORDER_NOT_FOUND`·409 `REFUND_*`·503 `USER_LOOKUP_UNAVAILABLE`용 예외·핸들러가 필요
- `payment-cancelled` 토픽은 serverC `KafkaTopicConfig`에 있음. Debezium은 `topic` 필드로 라우팅(`debezium/*.json`)이라 설정 변경 없음
- user-service `GET /internal/api/users/{loginId}` — 없으면 404(`UserNotFoundException`). cs-bot `CsBotClient.resolveNumericUserId`가 같은 호출
- Saga 보상 재고 복구 패턴: `SagaOrchestratorService.handleSagaFailure` (환불은 `releaseUserPurchase` 제외 — P-12)
- E2E에는 `user-service`·`mysql-user` 기동 필요 (현재 로컬 스택에서 꺼져 있음). 사용자 생성은 `POST /internal/api/users`(8086)

### 검토했으나 채택하지 않은 대안
#### PR② 하나로 유지
- 왜 안 썼나: 리뷰 범위가 넓다(약 30개). 배포 순서상 serverB·게이트웨이는 독립적으로 먼저 나갈 수 있다.
#### 게이트웨이 라우트 검증을 프로필별 `@SpringBootTest`로
- 왜 안 썼나: k8s 프로필은 클러스터 주소·환경변수를 전제로 해 로컬 컨텍스트 기동이 어렵다. 두 YAML 파일을 직접 읽어 순서·조건을 확인하는 편이 단순하고 두 설정을 모두 덮는다.

### 기존 코드베이스 컨벤션
- 게이트웨이 라우트: `application.yml`(local, `lb://`)·`application-k8s.yml`(서비스 DNS) 두 벌, 라우트마다 `RequestRateLimiter` + `ipKeyResolver`
- 계약: `contracts/openapi.yaml`(serverB)이 원본, `OpenApiContractTest`가 `/v3/api-docs`와 enum까지 비교 (`serverB/src/test/java/promotion/serverB/contract/OpenApiContractTest.java`)

### 관련 파일/위치
- `serverB/src/main/java/promotion/serverB/dto/OrderStatusResponse.java` — status `allowableValues`
- `contracts/openapi.yaml` — serverB 계약 (45행 근처 status enum)
- `gateway-service/src/main/resources/application.yml`(55행 `serverB-orders`), `application-k8s.yml`(20행)
- `gateway-service/src/main/java/com/example/gateway_service/filter/JwtAuthFilter.java` — 화이트리스트 밖은 JWT 필수 + `X-User-Id`·`X-User-Role` 주입 (환불 라우트도 자동 적용)

### 외부 참조
- 명세 `docs/specs/user-refund.md` "API"(게이트웨이·serverB), "이벤트"(배포 순서), "구현 단계"
