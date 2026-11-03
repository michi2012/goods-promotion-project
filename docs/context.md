# 맥락 노트: serverC PG 거래 조회 API + 멱등 키 기반 재시도 방어 (가정 구현)

## 왜 이 방식을 선택했는가

포트폴리오에 "PG 거래 조회 API로 상태 확정"과 "PG 멱등 키 헤더 전송"을 "구현하지 않음(개념으로만 설명)"이라고 정직하게 적어둔 상태였다. 이걸 실제 PG 연동 없이도 **Mock 레벨에서 그 패턴 자체**를 재현해볼 수 있는지 확인하는 작업이다.

실무에서 결제 네트워크 타임아웃은 두 겹 방어가 표준이라는 걸 사용자와 먼저 확인했다:
1. **조회(상태 확인)**: 응답을 못 받았을 때 무작정 재시도하지 않고, 먼저 "진짜 처리됐는지" 물어본다.
2. **멱등 키**: 조회로도 못 거르는 race condition(조회 시점엔 없었는데 재시도와 원래 요청이 거의 동시에 PG에 꽂히는 경우)을 PG가 같은 키로 걸러주는 최종 방어선.

이번 구현은 이 두 겹을 Mock 안에 그대로 넣는다. 멱등 키는 별도 헤더가 아니라 **이미 유니크한 `orderId`를 재사용**한다 — 국내 PG가 "주문번호 유일성"으로 멱등성을 보장하는 방식과 같은 패턴.

## 왜 진짜 타임아웃을 안 만들었는가

`MockPgClient.processPayment`는 `Thread.sleep(500)` 후 즉시 결과를 반환하는 고정 지연일 뿐, 실제로 "응답이 안 와서 포기"하는 메커니즘이 없다. `resilience4j.circuitbreaker` 설정(`application.yaml`)도 실패율 기준이라 호출 시간(TimeLimiter) 기준이 아니다. 진짜 타임아웃을 만들려면 `resilience4j.timelimiter` 설정 + `@TimeLimiter` + `CompletableFuture` 기반 비동기 호출로 바꿔야 하는데, 이는 범위가 커서 이번 "최소 범위 가정 구현"에서는 제외했다. 대신 **별도의 낮은 확률로 전용 예외(`PgAmbiguousResponseException`)를 던지는 것으로 "애매한 응답" 상황만 흉내**낸다.

## 구현 중 발견한 함정 — `@CircuitBreaker`의 fallback이 모든 예외를 가로챔

`MockPgClient.processPayment`엔 이미 `@CircuitBreaker(name = "pgClientCb", fallbackMethod = "fallbackProcessPayment")`가 붙어 있었다. `fallbackProcessPayment(PurchaseMessage, Throwable)`의 두 번째 파라미터가 `Throwable`이라 **모든 예외 타입을 다 받아줘서**, 새로 추가한 `PgAmbiguousResponseException`도 resilience4j 기본 동작상 fallback으로 가로채져 `PgPaymentException`으로 변환돼버렸다 — `PaymentService`가 `PgAmbiguousResponseException`을 구분해서 처리하려던 로직이 실제 런타임에서는 전혀 타지 않는 죽은 코드가 될 뻔했다.

**더 위험했던 점**: 이 문제는 `PaymentServiceTest`(Mockito로 `PgClient`를 mock 처리)에서는 전혀 드러나지 않는다 — 단위 테스트는 실제 resilience4j AOP 프록시를 거치지 않기 때문. 실제로 앱을 띄워서(docker-compose 등) 돌려야만 발견되는 종류의 버그였다. **단위 테스트 통과가 "AOP로 감싸인 메서드의 실제 런타임 동작까지 보장하지는 않는다"**는 걸 보여주는 사례로 기록해둔다.

**해결**: `application.yaml`의 `resilience4j.circuitbreaker.instances.pgClientCb`에 `ignoreExceptions: [PgAmbiguousResponseException]`을 추가 — 이 예외는 (a) 실패율 집계에서 제외되고(확실한 장애가 아니므로), (b) fallback을 안 타고 원래 예외 그대로 호출자에게 전달된다.

**검증 공백**: 이번 단위 테스트(`PaymentServiceTest`)로는 `ignoreExceptions` 설정이 실제로 작동하는지 자체는 검증하지 못한다 — Mockito mock은 애초에 `@CircuitBreaker` AOP를 거치지 않기 때문. 이 설정이 실제로 의도대로 동작하는지 확인하려면 `@SpringBootTest`로 실제 `MockPgClient` Bean(AOP 프록시 포함)을 띄우는 통합 테스트가 필요하다 — 이번 "최소 범위" 작업에서는 범위 밖으로 남겨둔다.

## 검토했으나 채택하지 않은 대안

### 대안 A: 기존 `PgPaymentException`을 재시도 트리거로 재사용
- 무엇: 서킷브레이커 open 시 던지는 기존 예외를 그대로 "애매한 응답"의 신호로도 사용.
- 왜 안 썼나: "서킷브레이커 open(PG가 확실히 죽었다고 판단됨)"과 "타임아웃(처리됐는지 모르겠다)"은 실무에서 서로 다른 상황이고 대응도 다르다(전자는 바로 포기, 후자는 조회 후 판단). 하나의 예외로 뭉개면 나중에 읽는 사람이 "이게 왜 재시도 로직을 타는지" 헷갈린다. 파일 하나(예외 클래스) 늘어나는 비용보다 의미를 정확히 하는 게 낫다고 판단해 새 예외(`PgAmbiguousResponseException`)를 추가하는 쪽을 선택했다.

### 대안 B: `queryTransaction` 반환값에 `PENDING` 포함(4종)
- 무엇: 실제 PG 흐름처럼 `PENDING`(처리 중)까지 포함한 4종 상태.
- 왜 안 썼나: 이 Mock은 비동기 지연 구조가 없어서 `PENDING`이 실제로 관찰될 상황이 없다 — 죽은 코드가 될 뿐이라 3종(`APPROVED`/`REJECTED`/`NOT_FOUND`)으로 줄였다.

## 기존 코드베이스 컨벤션과의 관계 — 멱등성 레이어 구분 (중요)

`PaymentRepository.claimOrder()`가 이미 멱등성을 처리하고 있지만, **이번에 추가하는 멱등성과 레이어가 다르다**:

| | `claimOrder` (기존) | 이번에 추가하는 것 |
|---|---|---|
| 막는 대상 | Kafka에서 같은 `purchase-request` 메시지가 **중복 수신**되는 경우 | 한 번 수신한 메시지를 처리하는 **중, PG 호출 자체를 재시도**할 때 중복 처리되는 경우 |
| 메커니즘 | DB 유니크 제약(`uk_order_id`)으로 선점 | `MockPgClient` 내부 맵에 orderId별 처리 결과를 캐시 |
| 호출 시점 | `PaymentService.processPayment` 진입 즉시, PG 호출 **이전** | PG 호출이 애매한 응답을 던진 **이후**, 재시도 직전 |

즉 `claimOrder`는 "이 주문을 내가 이미 처리하기 시작했는지"를 막고, 이번 추가분은 "이미 시작한 처리 안에서, PG 쪽 재시도가 중복 집계되는지"를 막는다. 서로 다른 문제라 중복이 아니다.

## 관련 파일/위치
- `serverC/src/main/java/promotion/serverC/service/PgClient.java` — 인터페이스, `queryTransaction` 추가 지점
- `serverC/src/main/java/promotion/serverC/service/MockPgClient.java` — 핵심 구현, 내부 상태 맵 + 멱등 캐시
- `serverC/src/main/resources/application.yaml` — `pgClientCb`의 `ignoreExceptions` 설정(계획 외 발견 사항, 위 섹션 참조)
- `serverC/src/main/java/promotion/serverC/service/PaymentService.java:55-85` — `processPayment`, 조회+재시도 분기 추가 지점. 기존 `catch (PgPaymentException e)` 블록(66-73행)은 그대로 두고 새 catch 블록을 나란히 추가.
- `serverC/src/test/java/promotion/serverC/service/PaymentServiceTest.java` — 기존 3개 테스트(Success/Fail/DuplicateMessage)는 무변경 통과 확인 대상, 신규 테스트 3개 추가
- `serverA/.../service/RedisStockService.java` — 참고용 "로컬 캐시로 재시도 트래픽 차단" 선례(Caffeine 품절 캐시). 이번 멱등 캐시는 TTL이 없다는 점이 다름(결제 결과는 영구히 기억해도 되는 값이라서).

## 외부 참조
없음. 이번 작업은 실무 일반론(조회+멱등 키 2중 방어)을 Mock으로 재현하는 학습 목적 구현.
