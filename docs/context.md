# 맥락 노트: PG 대사 PR① — 대사 기반 데이터 (BL-045)

## [진행 중] BL-002 PR① — BL-045

### 왜 이 방식을 선택했는가
- 요구사항·설계는 `docs/specs/pg-reconciliation-batch.md`에서 확정. 이 PR은 "구현 단계" ①.
- 시각은 **앱 시계**(`LocalDateTime.now()`)로 찍는다 (2026-11-17 사용자 결정). 모의 PG 원장 시각과 같은 JVM 시계라서 "PG 시각 ≤ 우리 기록 시각" 불변식(날짜 경계 판정의 전제)이 시계 어긋남과 무관하게 지켜진다.
- 원장 기록은 별도 빈 `MockPgLedger`의 `REQUIRES_NEW` — 외부 PG는 우리 트랜잭션 롤백과 무관하게 거래가 남는다. 같은 클래스 내부 호출은 프록시를 거치지 않아 별도 빈으로 분리.
- 상태 UPDATE에 시각을 붙이는 전용 메서드(`markPaid` 등). `updateOrderStatus`(목록·임의 상태)에 조건 분기를 넣는 것보다 의도가 드러나고, 기존 FAILED 경로는 그대로 둔다.

### 검토했으나 채택하지 않은 대안
- DB `NOW(6)`: 기존 `created_at`과 같지만 DB·JVM 시계가 다르면 불변식이 깨질 수 있음.
- `MockPgClient`가 CSV에 직접 덧붙이기: 동시 쓰기로 줄이 섞이고, 반쯤 쓴 파일을 배치가 읽을 수 있음.
- CSV 수동 재생성 API를 이 PR에: 대사 수동 실행 API(BL-046)와 함께 설계하는 편이 자연스러움.

### 기존 코드베이스 컨벤션
- serverC 리포지토리는 JdbcTemplate 직접 사용 (`PaymentRepository`). 리포지토리 테스트는 `@JdbcTest` + Testcontainers MySQL (`PaymentRepositoryTest`, `support/AbstractRepositoryTest`).
- 마이그레이션: `serverC/src/main/resources/db/migration/V{n}__*.sql` (현재 V3까지).
- 스케줄러: `scheduler/` 패키지, `@EnableScheduling`은 `config/SchedulingConfig`.
- 상태 문자열 상수: `PaymentCancelService`의 `PAID`·`CANCELLED`.

### 관련 파일/위치
- `serverC/.../service/MockPgClient.java` — 모의 PG (승인·조회·취소, 인메모리)
- `serverC/.../service/PaymentService.java` — 승인 처리, 금액 불일치 망취소(`failWithAmountMismatch`)
- `serverC/.../service/PaymentCancelService.java` — 취소 완료(`completeCancel`)
- `serverC/.../repository/PaymentRepository.java`

### 외부 참조
- 명세: `docs/specs/pg-reconciliation-batch.md`
