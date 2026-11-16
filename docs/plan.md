# 계획서: 사용자 환불 PR②-a — serverB 상태 enum + 게이트웨이 환불 라우트 (BL-029)

- 작성일: 2026-11-16
- 관련 이슈/티켓: BL-029 (`docs/backlog.md`), 명세 `docs/specs/user-refund.md` (설계 확인 ☑)

## [진행 중] BL-029 PR②-a

### 목표
환불로 생기는 주문 상태(`REFUND_REQUESTED`·`REFUNDED`)를 serverB 계약에 추가하고, 게이트웨이가 `POST /api/v1/orders/*/refund`를 serverA로 보내도록 라우트를 추가한다. 명세의 배포 순서(serverB → serverA → … → 게이트웨이)상 소비자 쪽을 먼저 내보내는 PR이다.

### 성공 기준
- [ ] serverB `GET /api/v1/orders/{orderId}/status` 응답 스키마의 status enum에 `REFUND_REQUESTED`·`REFUNDED`가 있고 계약(`contracts/openapi.yaml`)과 같다 (`serverB OpenApiContractTest`)
- [ ] 게이트웨이 `application.yml`·`application-k8s.yml` 모두 `serverA-refund`(Path=`/api/v1/orders/*/refund`, Method=POST → serverA, Rate limit 초당 20·버스트 50)가 `serverB-orders`보다 앞에 있다 (`GatewayRouteOrderTest` 신규)
- [ ] 기존 serverB·게이트웨이 테스트 통과 (`:serverB:test`, `:gateway-service:test`)
- [ ] 로컬 백엔드 E2E 회귀 통과 (`backend-e2e/ npm test` — 구매 흐름이 serverB 상태 조회까지 확인)

### 비범위 (Out of Scope)
- serverA 환불 API·`payment-cancelled` 컨슈머·`orders.paid_at`(V4)·user-service 조회·환불 E2E → **PR②-b** (②-a 머지 후 별도 `/plan`)
- serverB 로직 변경 (명세: 상태 문자열을 그대로 저장·응답, 회신은 PENDING일 때만)
- cs-bot·FAQ·프론트 → PR③

### 단계별 작업 계획

#### 단계 1: serverB 상태 enum + 계약
- 변경 파일: `serverB/src/main/java/promotion/serverB/dto/OrderStatusResponse.java`, `contracts/openapi.yaml`
- 변경 내용: `@Schema(allowableValues)`와 계약 status enum에 `REFUND_REQUESTED`, `REFUNDED`를 추가한다. 코드 로직 변경 없음.
- 검증: `.\gradlew.bat :serverB:test --tests "*OpenApiContractTest"`
- 롤백: `git checkout -- <위 파일>`
- 예상 소요: 짧음

#### 단계 2: 게이트웨이 환불 라우트
- 변경 파일: `gateway-service/src/main/resources/application.yml`, `application-k8s.yml`, 테스트 `gateway-service/src/test/java/com/example/gateway_service/GatewayRouteOrderTest.java`(신규)
- 변경 내용: `serverA-refund` 라우트를 `serverB-orders` 바로 앞에 추가 (local `lb://serverA`, k8s는 기존 serverA 라우트와 같은 주소). 테스트는 두 YAML을 읽어 라우트 순서·predicate·uri를 확인한다 (프로필별 컨텍스트를 띄우지 않고 파일 자체를 검증 — k8s 설정도 함께 확인하기 위해).
- 검증: `.\gradlew.bat :gateway-service:test` (기존 `contextLoads`·`JwtAuthFilterTest` 포함)
- 롤백: `git checkout -- <위 파일>`, 신규 테스트 삭제
- 예상 소요: 짧음

#### 단계 3: 명세·백로그 갱신
- 변경 파일: `docs/specs/user-refund.md`("구현 단계" 표에서 ②를 ②-a·②-b로 나눔), `docs/backlog.md`(BL-029 행)
- 검증: 육안
- 예상 소요: 짧음

#### 단계 4: 빌드 + 로컬 E2E 회귀
- 검증: `:serverB:build`, `:gateway-service:build` (백그라운드) → `:serverB:bootJar` 후 server-b 이미지만 재빌드(`up -d --build --no-deps server-b`) → `backend-e2e/ npm test`
- 롤백: 해당 없음
- 예상 소요: 보통

### 리스크 및 대응
- 리스크: `Path=/api/v1/orders/*/refund`가 serverB 조회 경로를 가로챔 → 대응: `Method=POST` 조건 + 테스트로 순서·조건 고정. serverB 조회는 모두 GET
- 리스크: ②-a만 배포된 동안 환불 라우트가 serverA 미구현 경로로 감 → 대응: 화면·cs-bot이 아직 호출하지 않아 무해 (PR③에서 호출 시작)
- 리스크: 게이트웨이 실제 트래픽 경로는 E2E로 검증하지 않음 (백엔드 E2E는 서버를 직접 호출) → 대응: 라우트 테스트로 설정을 고정. 게이트웨이 경유 확인은 PR②-b 이후 환불 API가 생기면 함께 판단

### 의존성
- 새 의존성 없음. DB 마이그레이션 없음
- PR②-b가 이 PR 머지 후 착수
