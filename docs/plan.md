# 계획서: serverC PG 거래 조회 API + 멱등 키 기반 재시도 방어 (가정 구현)

- 작성일: 2026-11-03
- 관련 이슈/티켓: 없음

## 목표
`MockPgClient`에 "애매한 응답(타임아웃성 신호) 발생 시, 재시도 전에 거래 조회로 먼저 확인하고, 멱등 키(orderId)로 중복 처리를 막는" 패턴을 가정(mock) 수준으로 구현한다. 실제 PG 연동이나 resilience4j TimeLimiter 기반 진짜 타임아웃은 범위 밖이다.

## 성공 기준
- [ ] `PgClient`에 `queryTransaction(orderId)` 메서드가 추가되고, `MockPgClient`가 3종 상태(`APPROVED`/`REJECTED`/`NOT_FOUND`)를 반환한다.
- [ ] `MockPgClient.processPayment`가 애매한 응답을 별도의 낮은 확률로 `PgAmbiguousResponseException`을 던져 시뮬레이션한다.
- [ ] `PaymentService.processPayment`가 이 예외를 잡아 `queryTransaction`으로 먼저 확인하고, `NOT_FOUND`일 때만 최대 1회 재시도한다.
- [ ] 멱등 키(orderId) 재사용 시, `MockPgClient`가 이미 기록된 결과를 캐시에서 그대로 돌려줘 중복 처리(예: 중복 환불 집계)가 안 일어난다.
- [ ] `gradlew :serverC:test --tests "*PaymentServiceTest*"` 통과 (기존 3개 테스트 무변경 통과 + 신규 테스트 추가 통과).
- [ ] `docs/context.md`에 기존 `claimOrder` 멱등성과의 레이어 차이가 명확히 기록된다.

## 비범위 (Out of Scope)
- resilience4j `TimeLimiter`를 이용한 진짜 호출 타임아웃 구현.
- 실제 PG사 HTTP 연동, 멱등 키를 HTTP 헤더로 전송하는 로직.
- DB 스키마 변경(마이그레이션 없음 — `orderId` 유니크 제약 재사용).
- serverA/프론트엔드/Kafka 메시지 계약 변경.

## 단계별 작업 계획

### 단계 1: `PgTransactionStatus` enum + `PgAmbiguousResponseException` 추가
- 변경 파일: `serverC/src/main/java/promotion/serverC/service/PgTransactionStatus.java`(신규), `serverC/src/main/java/promotion/serverC/exception/PgAmbiguousResponseException.java`(신규)
- 변경 내용 요약: 3종 상태 enum과, 애매한 응답을 나타내는 전용 런타임 예외(기존 `PgPaymentException`과 분리)를 추가한다.
- 검증 방법: `gradlew :serverC:compileJava`
- 롤백 방법: 두 파일 삭제
- 예상 소요: 짧음

### 단계 2: `PgClient` 인터페이스에 `queryTransaction` 추가
- 변경 파일: `serverC/src/main/java/promotion/serverC/service/PgClient.java`
- 변경 내용 요약: `PgTransactionStatus queryTransaction(String orderId)` 메서드 시그니처 추가.
- 검증 방법: `gradlew :serverC:compileJava` (구현체 미구현 시 컴파일 에러로 다음 단계 필요성 확인)
- 롤백 방법: 메서드 시그니처 제거
- 예상 소요: 짧음

### 단계 3: `MockPgClient`에 내부 상태 저장 + 조회/멱등 로직 구현
- 변경 파일: `serverC/src/main/java/promotion/serverC/service/MockPgClient.java`
- 변경 내용 요약: `ConcurrentHashMap<String, PgTransactionStatus>`로 orderId별 결과를 기록. `processPayment`는 (a) 이미 기록된 키면 캐시된 결과를 즉시 반환(멱등), (b) 낮은 확률로 `PgAmbiguousResponseException`을 던짐(재시도 트리거), (c) 그 외엔 기존 로직대로 처리 후 결과를 기록. `queryTransaction`은 맵을 조회해 `NOT_FOUND` 또는 기록된 상태를 반환.
- 검증 방법: `gradlew :serverC:compileJava`
- 롤백 방법: 파일을 이전 버전으로 되돌림(git checkout)
- 예상 소요: 보통

### 단계 4: `PaymentService.processPayment`에 조회+재시도 분기 추가
- 변경 파일: `serverC/src/main/java/promotion/serverC/service/PaymentService.java`
- 변경 내용 요약: 기존 `catch (PgPaymentException e)` 블록과 별도로 `catch (PgAmbiguousResponseException e)` 블록을 추가. 이 블록에서 `queryTransaction` 호출 → `NOT_FOUND`면 1회 재시도(재시도 중 또 애매하면 `FAILED` 처리) → 그 외 상태면 조회 결과를 최종값으로 채택.
- 검증 방법: `gradlew :serverC:compileJava`
- 롤백 방법: 메서드를 이전 버전으로 되돌림
- 예상 소요: 보통

### 단계 5: 테스트 작성 및 기존 테스트 영향 확인
- 변경 파일: `serverC/src/test/java/promotion/serverC/service/PaymentServiceTest.java`
- 변경 내용 요약: 기존 3개 테스트(Success/Fail/DuplicateMessage)는 시그니처 무변경이므로 그대로 유지하고 통과 재확인. 신규 테스트 추가: ① 애매한 응답 후 조회가 `NOT_FOUND` → 재시도 성공, ② 애매한 응답 후 조회가 `APPROVED` → 재시도 없이 그 결과 채택, ③ 재시도도 애매함 → `FAILED` 처리.
- 검증 방법: `gradlew :serverC:test --tests "*PaymentServiceTest*"`
- 롤백 방법: 테스트 파일을 이전 버전으로 되돌림
- 예상 소요: 보통

### 단계 6: `docs/context.md` 마무리 및 전체 검증
- 변경 파일: `docs/context.md`, `docs/checklist.md`
- 변경 내용 요약: 기존 `claimOrder` 멱등성과의 레이어 차이, 채택하지 않은 대안(옵션 B: 기존 예외 재사용) 기록. 전체 `gradlew :serverC:test` 재실행.
- 검증 방법: `gradlew :serverC:test`
- 롤백 방법: 문서만 되돌림(코드 영향 없음)
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크 1: 낮은 확률의 랜덤 예외가 테스트에서 우연히 또 발생해 재시도도 실패하는 flaky 상황 → 대응: 프로덕션 코드의 랜덤 확률과 무관하게, 테스트에서는 Mockito로 예외/반환값을 결정론적으로 강제 지정(랜덤에 의존하지 않음).
- 리스크 2: `PgAmbiguousResponseException`과 기존 `PgPaymentException` catch 블록 순서가 뒤바뀌면 의도와 다르게 잡힐 수 있음 → 대응: 두 예외는 상속 관계가 아닌 독립된 `RuntimeException` 하위 클래스로 만들어 catch 순서 문제 자체를 없앰.

## 의존성
없음 (serverC 내부에 완전히 국한, 외부 서비스/DB 스키마 의존성 없음).
