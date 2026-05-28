# 맥락 노트: serverC 결제 조회 API 추가

## 왜 이 방식을 선택했는가
결제 도메인의 소유자가 serverC이므로 결제 조회도 serverC가 직접 서빙한다.
serverB에 복제본을 두면 동기화 Kafka 토픽/Consumer/정합성 로직이 필요해 복잡도가 과도하게 증가한다.
serverB의 `serverCClient` Circuit Breaker가 이미 선언되어 있으므로,
외부 노출이 필요하면 serverB가 serverC를 HTTP 중계하는 방식도 가능하다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 별도 PaymentQueryService 분리
- 무엇: 조회 전용 서비스 클래스를 따로 만들기
- 왜 안 썼나: 조회 메서드 2개를 위한 클래스 분리는 과도함. 기존 PaymentService에 추가가 더 단순.

### 대안 B: serverB에 결제 데이터 복제 후 조회
- 무엇: Kafka로 결제 이벤트를 serverB에 동기화해서 serverB에서 조회
- 왜 안 썼나: 동기화 인프라(토픽, Consumer, 정합성) 복잡도 과도. serverC 직접 조회가 훨씬 단순.

## 기존 코드베이스 컨벤션
- 응답 형식: `ResponseEntity<T>` 직접 반환 (serverB 패턴 참조: OrderQueryController)
- 공통 ApiResponse 래퍼 없음
- 패키지: 레이어 중심 (controller/, service/, repository/, dto/, exception/)
- DTO: Java record 사용
- 예외: RuntimeException 상속, GlobalExceptionHandler에서 ResponseEntity 반환

## 관련 파일/위치
- `serverC/.../dto/PaymentResponse.java` — 응답 DTO (신규)
- `serverC/.../repository/PaymentRepository.java` — JDBC 조회 메서드 추가
- `serverC/.../service/PaymentService.java` — 조회 서비스 메서드 추가
- `serverC/.../controller/PaymentController.java` — HTTP 엔드포인트 (신규)
- `serverC/.../exception/PaymentNotFoundException.java` — 404 예외 (신규)
- `serverC/.../exception/GlobalExceptionHandler.java` — 404 핸들러 추가
