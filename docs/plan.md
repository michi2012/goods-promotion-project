# 계획서: serverC 결제 조회 API 추가

- 작성일: 2026-05-28

## 목표
serverC에 결제 조회 HTTP 엔드포인트 2개를 추가한다.
- `GET /api/v1/payments/{orderId}` — 주문 ID로 단건 조회
- `GET /api/v1/payments/users/{userId}` — 사용자별 결제 내역 (page/size 페이징)

## 성공 기준
- [ ] `GET /api/v1/payments/{orderId}` — 존재하면 200 + PaymentResponse, 없으면 404
- [ ] `GET /api/v1/payments/users/{userId}` — 200 + List<PaymentResponse> (page/size 파라미터)
- [ ] 응답에 PII(email, phone, address 등) 미포함
- [ ] Controller에 비즈니스 로직 없음, Entity 직접 반환 없음
- [ ] arch-snapshot.md serverC API 테이블 업데이트

## 비범위 (Out of Scope)
- 인증/인가 추가
- serverB를 통한 결제 조회 중계
- 결제 수정/삭제 API

## 단계별 작업 계획

### 단계 1: dto/PaymentResponse.java 신규 생성
- 변경 파일: `serverC/src/main/java/weverse/serverC/dto/PaymentResponse.java`
- 변경 내용: record 타입, 핵심 필드(orderId, userId, goodsId, quantity, paymentMethod, status, createdAt)
- 검증 방법: 파일 생성 확인
- 예상 소요: 짧음

### 단계 2: repository/PaymentRepository.java — 조회 메서드 추가
- 변경 파일: `serverC/src/main/java/weverse/serverC/repository/PaymentRepository.java`
- 변경 내용:
  - `findByOrderId(String orderId)` → `Optional<PaymentResponse>`
  - `findByUserId(Long userId, int page, int size)` → `List<PaymentResponse>` (LIMIT/OFFSET)
- 검증 방법: 코드 확인
- 예상 소요: 짧음

### 단계 3: service/PaymentService.java — 조회 메서드 추가
- 변경 파일: `serverC/src/main/java/weverse/serverC/service/PaymentService.java`
- 변경 내용:
  - `getByOrderId(String orderId)` — PaymentNotFoundException 발생
  - `getByUserId(Long userId, int page, int size)` — 목록 반환
- 검증 방법: 코드 확인
- 예상 소요: 짧음

### 단계 4: exception/PaymentNotFoundException.java 신규 + GlobalExceptionHandler 수정
- 변경 파일: `serverC/.../exception/PaymentNotFoundException.java` (신규), `GlobalExceptionHandler.java` (수정)
- 변경 내용: 404 RuntimeException + 핸들러 등록
- 검증 방법: 코드 확인
- 예상 소요: 짧음

### 단계 5: controller/PaymentController.java 신규 생성
- 변경 파일: `serverC/src/main/java/weverse/serverC/controller/PaymentController.java`
- 변경 내용: GET 2개, serverB 패턴 (`ResponseEntity<T>`) 동일하게 적용
- 검증 방법: 코드 확인
- 예상 소요: 짧음

### 단계 6: docs/arch-snapshot.md 업데이트
- 변경 파일: `docs/arch-snapshot.md`
- 변경 내용: serverC API 엔드포인트 테이블 추가
- 예상 소요: 짧음

## 리스크 및 대응
- JDBC RowMapper 작성 중 컬럼명 오타 → 쿼리와 payments 테이블 DDL 대조 확인
- page=0, size=0 등 잘못된 파라미터 → size 최솟값 1, page 최솟값 0 기본값 보정

## 의존성
- `payments` 테이블 컬럼명은 Payment 엔티티 기준 (order_id, user_id, goods_id, payment_method, status, created_at)
