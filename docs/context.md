# 맥락 노트: 사용자 환불 + 환불 실패 재처리 (BL-029)

## [진행 중] PR① serverC — PG 취소 멱등화 + 실패 재처리

### 왜 이 방식을 선택했는가
- 정책·계약은 `docs/specs/user-refund.md`에서 확정했다 (결정 기록 표 참고). 이 문서에는 구현 단위의 결정만 적는다.
- **PR 3개 분리**: 변경 파일이 약 45개라 리뷰 기준(약 20개)을 넘는다. serverC를 먼저 내는 이유는 단독으로도 쓸모가 있고(망취소 실패 재처리), 소비자가 없어도 `payment-cancelled` 발행이 무해하기 때문이다.
- **실패 경로 테스트**: serverC 통합 테스트(Testcontainers + PgClient 대체)로 검증한다. 운영 코드에 테스트 전용 실패 주입 API를 넣지 않는다. 백엔드 E2E는 PR②에서 정상 환불·중복 요청을 다룬다.
- **PG 실패 시 예외를 다시 던진다**: 기존 Kafka 재시도(3회 지수 백오프) → DLT 흐름을 그대로 쓰고, DLT 핸들러에서 실패 행을 만든다. 컨슈머 안에서 직접 실패 행을 만들면 일시 장애도 바로 행이 된다.
- **새 테이블은 JPA 엔티티**: 상태 전이가 있어 도메인 메서드로 표현한다. 기존 `PaymentRepository`(JdbcTemplate)는 건드리지 않고 필요한 조건부 UPDATE만 추가한다.

### 검토했으나 채택하지 않은 대안
#### 실패 주입 API (local 프로필 전용)
- 무엇: serverC에 "취소 실패 켜기/끄기" 내부 API를 두고 E2E에서 실패 경로까지 검증
- 왜 안 썼나: 테스트 전용 코드가 운영 코드에 들어간다. 실패 경로는 통합 테스트로 충분하다고 사용자와 합의

#### ShedLock으로 스케줄러 중복 실행 방지
- 무엇: 분산 락 라이브러리
- 왜 안 썼나: 새 의존성. 행 단위 조건부 UPDATE 선점 + PG 멱등으로 충분 (명세 "새 기술: 없음")

#### payments.status에 CANCEL_FAILED 추가
- 왜 안 썼나: serverA 결제조회(`PaymentQueryClient`)·재대사가 PAID 여부로 판단한다. 상태를 늘리면 그쪽 해석이 바뀐다. 실패는 별도 테이블에만 둔다 (명세)

### 기존 코드베이스 컨벤션
- 패키지: `promotion.serverC.{controller,dto,entity,exception,kafka,outbox,repository,service,config}`. 스케줄러 패키지는 serverA처럼 `scheduler/`를 새로 만든다
- Outbox 저장: `OutboxEventService.save(aggregateId, topic, payload)` — 호출자 트랜잭션 안
- 메트릭: `@PostConstruct`에서 `Counter.builder("business_payment_...")` (`PaymentCancelConsumer` 참고)
- 예외 처리: `exception/GlobalExceptionHandler.java`
- 테스트: 리포지토리는 `@JdbcTest` + Testcontainers MySQL (`repository/PaymentRepositoryTest.java`), 서비스는 Mockito (`service/PaymentServiceTest.java`), 컨트롤러는 `@WebMvcTest` (`controller/PaymentControllerTest.java`)

### 관련 파일/위치
- `serverC/.../kafka/PaymentCancelConsumer.java` — payment-cancel 소비, DLT 핸들러
- `serverC/.../service/PgClient.java`, `MockPgClient.java` — PG 취소
- `serverC/.../service/PaymentService.java` — 금액 불일치 즉시 망취소 (호출부만 변경)
- `serverC/.../repository/PaymentRepository.java` — payments 상태 변경
- `serverC/.../config/KafkaConsumerConfig.java` — 재시도 3회·DLT 설정 (변경 없음)
- `debezium/payment-outbox-connector.json` — topic 컬럼 라우팅 (변경 없음)
- `monitoring/prometheus/alert-rules.yml`, `helm/promotion-monitoring/files/alert-rules.yml` — P0 `PaymentFatalError`

### 외부 참조
- 명세: `docs/specs/user-refund.md`
