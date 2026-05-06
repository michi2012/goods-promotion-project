

## 🚀데이터 플로우 :




## 1. 서버 A 시스템 아키텍처 및 상세 데이터 흐름 (Data Flow) :
본 시스템은 1vCPU의 한계를 극복하기 위해 API 수신부와 비즈니스 처리부를 완벽히 분리한 비동기 이벤트 기반 워커(Worker) 아키텍처를 채택했습니다. 사용자의 요청은 다음 4단계의 세밀한 파이프라인을 거쳐 처리됩니다.

### Phase 1. 트래픽 수신 및 부하 차단 (API Gateway Layer)
- **요청 수신:** 사용자가 PromotionController로 구매 요청을 보내면, 고유한 traceId가 발급됩니다.
- **Fail-Fast (부하 차단):** PromotionService.acceptPurchase에서 최대 10,000개의 용량을 가진 ArrayBlockingQueue에 offer() 메서드로 진입을 시도합니다.
- **응답 분기:**
    - **성공:** 큐에 안전하게 적재되면 DB 연산을 기다리지 않고 즉시 202 Accepted를 반환하여 Tomcat 커넥션을 해제합니다.
    - **실패 (유량 제어):** 큐가 포화 상태일 경우, 대기(Blocking)하지 않고 즉시 QueueFullException을 던져 503 Service Unavailable로 튕겨냅니다. (서버 붕괴 방지)

### Phase 2. 비동기 큐잉 및 영구 저장 (DB I/O Layer)
- **Flusher 스케줄러 작동:** @Scheduled(fixedDelay = 100) 설정에 따라 0.1초마다 메모리 큐를 확인합니다.
- **메모리 정렬 (데드락 방지):** drainTo로 최대 500건의 데이터를 꺼낸 뒤, DB 인서트 시 발생하는 B-Tree 인덱스 갭 락(Gap Lock) 충돌을 막기 위해 traceId 기준으로 오름차순 정렬(Sort)을 수행합니다.
- **Bulk Insert:** OutboxBatchService가 JdbcTemplate을 활용해 단일 커넥션으로 500건을 한 번에 RDBMS에 PENDING 상태로 적재합니다.
- **Fallback 처리:** RDBMS 장애로 저장이 실패할 경우, 선착순 도메인 특성을 반영하여 지연 재처리(Retry)를 포기하고, CS 보상 처리용 Audit Log(fallbackToLogFile)로 직렬화하여 데이터를 안전하게 격리합니다.

### Phase 3. 재고 차감 및 동시성 제어 (Business Worker Layer)
- **Worker 스케줄러 작동:** @Scheduled(fixedDelay = 1000) 설정으로 DB CPU 경합을 막기 위해 1초마다 PENDING 데이터를 500건씩 조회합니다.
- **1차 중복 방어 (Memory):** ConcurrentHashMap(userCache)의 putIfAbsent를 활용하여 이미 대기열에 진입한 유저의 중복 요청을 O(1) 속도로 차단합니다.
- **2차 중복 방어 (DB):** OutboxProcessor 내의 독립된 트랜잭션(REQUIRES_NEW)에서 existsByUserIdAndStatusIn 쿼리로 최종 결제 이력을 검증합니다.
- **원자적 재고 차감 (Atomic Update):** goodsRepository.decreaseStockAtomically를 호출하여 레코드 락(Row Lock) 점유 시간을 최소화하며 재고를 차감합니다. 성공 시 SUCCESS, 실패 시(품절 등) FAIL로 상태를 마킹(noRollbackFor 적용)합니다.

### Phase 4. 글로벌 캐시 및 이벤트 전파 (Event Propagation Layer)
- **품절 감지:** 재고 차감 시 결과가 0건이거나 잔여 재고가 0이 되면 EventNotifier.notifySoldOutToServerB를 비동기 호출합니다.
- **Hot Spot 방어 (Atomic Lock):** 수많은 워커가 동시에 Server B로 알림을 쏘는 폭주(Cache Stampede)를 막기 위해, recentlyNotified.compute()를 사용하여 해당 상품 ID에 대한 원자적 락을 걸고 5초의 쿨다운을 적용해 단 1회의 알림만 전파되도록 제어합니다.

### Phase 5. 아웃박스 생명주기 관리 및 자가 복구
- **좀비 메시지 초고속 복구 (15초 룰):** 장애로 인해 PUBLISHING 상태에서 멈춰버린 '좀비 데이터'를 5초 주기의 스케줄러가 탐지합니다. 선착순 도메인의 빠른 호흡을 반영하여, 15초 이상 지연된 데이터를 즉시 PENDING으로 원복(Self-Healing)시켜 사용자의 억울한 기회 박탈을 방지합니다.
- **아웃박스 Garbage Collection:** 매일 새벽 4시, 트래픽이 가장 적은 시간에 처리가 완료된 Outbox 데이터를 DB에서 삭제합니다. 테이블 락(Lock)을 방지하기 위해 Native Query를 사용하여 LIMIT 5000의 청크(Chunk) 단위로 분할 삭제하며, DB의 인덱스 크기와 스캔 성능을 최상으로 유지합니다.

## 2. 서버 B 시스템 아키텍처 및 상세 데이터 흐름 (Data Flow) :
### Phase 1. 트래픽 수신 및 In-Flight 유량 제어 (Gateway Layer)
- **Bulk 요청 수신:** Server A의 스케줄러가 보낸 대량의 PurchaseMessage를 ProcessController.receiveBulk에서 수신합니다.
- **동시성 방어 (Backpressure):** AtomicInteger 기반의 currentInFlightRequests를 사용하여, 현재 처리 중인 요청이 MAX_IN_FLIGHT(20,000건)를 초과하면 즉시 SystemOverloadException을 발생시켜 서버 메모리 터짐(OOM)을 방지합니다.

### Phase 2. O(1) 멱등성 검증 및 Redis Pipelining (Processing Layer)
- **Bulk 멱등성 검증:** 재시도 로직으로 인한 중복 데이터를 막기 위해, 수신된 메시지들의 traceId를 Redis multiGet으로 한 번에 조회하여 O(1) 속도로 새로운 메시지만 필터링합니다.
- **원자적 Pipelining 처리:** 필터링된 메시지들은 redisTemplate.executePipelined를 통해 단 한 번의 네트워크 I/O로 다음 세 가지 작업을 원자적으로 수행합니다.
    - **Hash:** 유저 주문 상태를 PROCESSING으로 즉시 업데이트 (프론트엔드 실시간 조회용)
    - **String:** 멱등성 방어용 traceId 기록 (TTL 1시간)
    - **Stream:** Server C로 보낼 데이터를 queue:to_server_c 스트림에 적재

### Phase 3. 분산 큐잉 및 Pending 장애 복구 (Stream Worker Layer)
- **실시간 스트림 소비:** QueueToCWorker의 첫 번째 스케줄러(100ms)가 Redis Stream에서 최대 500건씩 데이터를 읽어와 Server C의 Bulk API로 전송합니다.
- **ACK 및 DLQ:** Server C에서 명확한 2xx 응답을 받았을 때만 Stream 메시지를 acknowledge 및 delete 처리합니다. 데이터 파싱 에러나 400 Bad Request 발생 시 해당 메시지는 queue:dead_letters (DLQ)로 즉시 격리합니다.
- **고아 메시지(Pending) 복구:** 서버 B가 배포/다운되어 읽어만 두고 ACK를 못한 메시지를 복구하기 위해, 두 번째 스케줄러(5초)가 ReadOffset.from("0")을 조회합니다. 이때 임의의 UUID가 아닌 고정된 Consumer ID(server-b-worker-1)를 사용하여, 자신이 처리하다 만 데이터를 정확히 찾아내어 재처리합니다.

### Phase 4. Saga 패턴 기반 보상 트랜잭션 (Saga & Compensation Layer)
- **부분 실패 감지:** Server C(결제/영구저장)에서 잔액 부족 등으로 일부 결제가 실패할 경우, 응답 객체(ServerCResponse)의 실패한 traceId 리스트를 기반으로 성공/실패를 분리합니다.
- **보상 트랜잭션 트리거:** 결제 실패로 판명된 건에 대해 즉시 triggerCompensationToServerA를 호출하여, Server A에게 "재고 차감을 롤백하라"는 보상 트랜잭션(Compensation) API를 요청합니다.
- **Eventual Consistency (좀비 워커):** 만약 보상 요청 시 Server A가 다운되어 있다면, 보상 데이터를 Redis의 queue:retry_compensate_to_a 리스트에 밀어 넣습니다. 세 번째 스케줄러(3초)가 이 큐를 끝까지 추적하며 Server A가 살아날 때까지 재시도하여 최종 일관성(Eventual Consistency)을 완벽히 보장합니다.

### Phase 5. 다층 캐시 기반 조회 부하 분산 (Read-Heavy Defense Layer)
- **재고 조회 (Cache Stampede 방어):** 프론트엔드의 실시간 재고 조회 시, Caffeine 로컬 캐시를 1차로 확인합니다. 캐시 미스 발생 시 get(key, function) 내부의 동기화 락(Lock) 메커니즘을 통해, 1만 명이 동시에 접근해도 단 1명의 스레드만 Redis를 조회하게 하여 DB 및 Redis 부하를 원천 차단합니다.
- **품절 전파:** 잔여 재고가 0이 되면 즉시 글로벌 Redis 및 로컬 soldOutCache에 플래그를 세워, 이후의 모든 유입을 로컬 메모리 단에서 0.001초 만에 0으로 응답합니다.

## 3. 서버 B 시스템 아키텍처 및 상세 데이터 흐름 (Data Flow) :
### Phase 1. 벌크 요청 수신 및 Chunk 메모리 분할 (Data Partitioning)
- **벌크 수신:** Server B의 Stream Worker가 전송한 최대 500건의 PurchaseMessage를 OrderController.receiveBulkOrders에서 수신합니다.
- **Chunk 분할:** RDBMS의 IN 절 파라미터 개수 제한(Limits)을 회피하고 락(Lock) 점유 시간을 줄이기 위해, partition() 유틸리티를 사용하여 대량의 데이터를 다시 적절한 사이즈(500건 단위)의 Chunk로 쪼개어 순차 처리합니다.

### Phase 2. RDBMS Unique 제약 기반 멱등성 검증 (Idempotency Layer)
- **이중 결제 원천 차단:** Server B에서 Redis 기반으로 1차 멱등성을 검증했더라도, 분산 시스템의 특성상 최종 DB에서의 검증이 필수적입니다.
- **INSERT IGNORE 로직:** FinalOrderRepository.claimOrders에서 INSERT IGNORE 쿼리와 JdbcTemplate.batchUpdate를 사용하여 RDBMS의 Unique Index(trace_id) 충돌을 활용합니다.
- **선점(Claim) 확인:** 쿼 실행 결과 배열에서 성공(1 또는 -2)한 건수만 필터링하여 "현재 스레드가 DB에 적재를 성공한 순수한 신규 주문"만 발라냅니다. 모두 중복일 경우 즉시 다음 Chunk로 넘어갑니다.

### Phase 3. DB Connection-Free 외부 API 통신 (PG Payment Layer)
- **커넥션 풀 분리 (핵심 최적화):** DB에 주문을 선점(PENDING 상태)한 직후, 트랜잭션을 열지 않은 상태(No DB Connection)에서 pgClient.processPayments를 호출하여 PG사 결제를 시도합니다.
- **스레드 대기 고립:** PG사 응답이 500ms 이상 지연되더라도, HikariCP 커넥션을 물고 있지 않기 때문에 DB 커넥션 풀이 고갈되는 대형 장애(Connection Pool Exhaustion)를 완벽히 차단합니다.

### Phase 4. 최종 상태 업데이트 및 예외 보상 (Saga & Rollback Layer)
- **수동 트랜잭션 제어:** PG 결제 응답(성공/실패 리스트)을 받은 후, @Transactional 어노테이션 대신 TransactionTemplate.executeWithoutResult를 사용하여 상태 업데이트 구간만 아주 짧게 트랜잭션을 엽니다.
- **정상 처리:** updateOrderStatus를 호출하여 PG 승인 건은 SUCCESS, 거절 건은 FAIL로 DB를 업데이트합니다.
- **망취소 (결제 강제 취소) 롤백:** 상태를 SUCCESS로 업데이트하는 도중 DB 장애(Crash)가 발생할 경우를 대비한 catch 로직이 발동합니다.
    - 이미 고객의 돈이 결제(PG 승인)되었으나 DB에 기록을 못 하는 최악의 상황이므로, 즉각 pgClient.cancelPayments를 호출하여 고객의 결제를 강제 취소(환불) 처리합니다.
    - 이후 해당 건들을 Server B에 반환할 totalFailedTraceIds에 포함시켜, Server B가 Server A로 재고 롤백 지시(보상 트랜잭션)를 내리도록 분산 트랜잭션 파이프라인의 최종 일관성을 완성합니다.



## 🚀 핵심 평가 항목 해결 방안

### 1. 대량 트래픽과 동시성 제어

#### [Server A]
- **커넥션 풀 고갈 방어 (Decoupling):** 수천 개의 요청을 즉시 수용하는 API 수신 스레드와, 실제 DB에 INSERT를 수행하는 워커 스레드를 메모리 큐를 통해 비동기로 분리했습니다. 이를 통해 트래픽이 아무리 폭주해도 DB 커넥션은 제한된 워커 스레드의 수만큼만 통제된 상태로 사용되므로, 1vCPU 환경에서 가장 흔히 발생하는 HikariCP 고갈 현상을 방지했습니다.
- **DB 락 최소화:** 비관적 락(SELECT FOR UPDATE) 대신 원자적 업데이트 쿼리를 사용하고, Bulk Insert 전 메모리에서 Index 기준으로 정렬하여 데드락을 방지했습니다.

#### [Server B]
- MAX_IN_FLIGHT 설정을 통해 동시 진입 트래픽의 상한선을 물리적으로 통제했습니다.
- 대량의 인서트/업데이트 로직을 Redis의 executePipelined로 묶어서 처리하여, 단일 코어 환경에서의 네트워크 I/O 병목 및 Context Switching 오버헤드를 최소화했습니다.

#### [Server C]
- **지연 격리:** 시스템 병목의 주범인 외부 API(PG) 통신 구간을 RDBMS 트랜잭션 바깥으로 완전히 분리했습니다. 결과적으로 DB Connection 점유 시간은 INSERT IGNORE와 UPDATE가 실행되는 시간이 압축되어 1vCPU 환경에서도 HikariCP 고갈을 방지합니다.
- **네트워크 I/O 및 트랜잭션 오버헤드 최소화:** 대량의 주문 데이터를 DB에 반영할 때 발생하는 애플리케이션과 DB 간의 불필요한 네트워크 왕복을 청크(Chunk) 단위로 압축했습니다. 초기 주문 선점(INSERT) 시에는 JdbcTemplate의 batchUpdate를 적용하여 한 번의 I/O로 처리했고, 결제 상태 변경(UPDATE) 시에는 개별 업데이트 루프 대신 동적 IN 쿼리를 조합한 단일 쿼리를 사용했습니다. 이를 통해 1vCPU 환경의 치명적인 병목인 DB 커넥션 점유 시간과 I/O 오버헤드를 극단적으로 줄였습니다.

---

### 2. 분산 서비스 간 데이터 정합성

#### [Server A]
- **조회 후 보상:** 타임아웃 발생 시 맹목적으로 보상 트랜잭션을 실행하지 않고, Server C의 최종 결제 상태를 먼저 조회(checkPaymentStatusAtServerC)하는 절차를 추가했습니다. 이를 통해 '지연된 성공'과 '성급한 보상'이 충돌하여 100개의 재고로 101건이 결제되는 레이스 컨디션(유령 재고) 문제를 완벽히 차단했습니다.
- **Graceful Shutdown:** 서버 강제 종료 시 @PreDestroy를 통해 메모리 큐의 잔여 데이터를 RDBMS에 강제 Flush 하여 유실을 방지합니다.
- **도메인 맞춤형 DLQ:** RDBMS마저 다운되어 저장이 불가능한 최악의 경우, 억지스러운 지연 재처리(Retry) 대신 고객 CS 보상을 위한 파일 로그로 데이터를 안전하게 격리합니다.
- **등가성(Idempotency):** 로컬 캐시(userCache)를 통한 1차 방어와 DB EXISTS 쿼리를 통한 2차 방어로 새로고침 연타 시 발생하는 중복 주문을 방지했습니다.
- **선착순 도메인 맞춤형 자가 복구:** 일반적인 5분~10분 주기의 아웃박스 복구는 선착순 도메인에서 무의미합니다. 따라서 임계치를 15초로 극단적으로 단축하여, 발행 중(PUBLISHING) 죽어버린 Outbox 좀비 메시지를 PENDING으로 원복시킴으로써 최종 일관성을 확보합니다.

#### [Server B]
- **Idempotency(등가성):** Stream 큐에 넣기 전 Redis multiGet을 활용해 전체 트랜잭션의 멱등성을 O(1) 시간복잡도로 검증했습니다.
- **장애 유실 방지 (Saga Pattern):** Server C에서 로직이 실패했을 때 데이터 정합성이 깨지는 것을 막기 위해, Server A로 롤백 지시를 내리는 Choreography Saga 패턴의 보상 트랜잭션을 구현했습니다. 특히 서버 간 통신 장애 시 Redis List 기반의 자체 Retry 큐를 구성하여 장애 격리 및 최종 일관성을 보장했습니다.
- **Stream Pending 복구:** 고정된 Consumer Name과 ReadOffset.from("0")을 활용하여, 서버가 갑자기 죽더라도 처리 중이던 메시지가 증발하지 않고 재시작 시 자동으로 복구되도록 구현했습니다.

#### [Server C]
- **Idempotency (등가성 보장):** RDBMS의 물리적 Unique 제약조건과 INSERT IGNORE를 활용하여, 네트워크 재시도나 서버 재시작으로 동일한 메시지가 n회 들어오더라도 이중 결제가 발생하지 않도록 최종 방어막을 구축했습니다.
- **분산 트랜잭션 에러 복구 (Saga 망취소):** 로컬 DB 트랜잭션 실패 시, 이미 완료된 외부 시스템(PG사)의 상태를 원래대로 되돌리는(환불) 로직(cancelPayments)을 구현했습니다.

---

### 3. 캐시 설계 및 Hot Spot 문제 해결

#### [Server B]
- 특정 상품에 대한 재고 조회가 폭주하는 Hot Spot 현상을 방어하기 위해 로컬(Caffeine) - 글로벌(Redis)의 다층 캐시 구조를 설계했습니다.
- 다수의 쓰레드가 동시에 캐시 미스를 겪어 Redis로 일제히 몰리는 Cache Stampede 현상을 방어하기 위해 Caffeine의 동기화 함수(get(key, function))를 사용하여 락(Lock)을 획득한 단일 스레드만 외부 I/O를 수행하도록 최적화했습니다.

#### [Server C]
- Server C는 최종 영구 저장소의 역할을 하므로, 실시간 동기 처리 대신 Redis Stream을 쓰기 버퍼(Write Buffer)로 활용하여 부하를 분산시켰습니다. List의 partition() 메서드를 통해 유입된 데이터를 최대 500건 단위의 Chunk로 쪼개어 순차적으로 DB에 밀어 넣음으로써(Bulk Insert), 대량 트래픽 쇄도 시에도 리소스 한계에 도달하지 않도록 쓰기 속도를 안정적으로 조절했습니다.

---

### 4. 유량 조절 (Rate Limiting / Backpressure) 및 Circuit Breaker

#### [Server A]
- 수신 한계(1vCPU)를 초과하는 트래픽으로 인한 큐 오버플로우 및 연쇄 장애를 막기 위해 Fail-Fast 전략을 적용했습니다. 큐 Capacity를 타이트하게 설정하고 offer() 메서드를 통해 초과 유입분에 대해 즉각적으로 HTTP 503 거절 응답을 반환함으로써, 수용된 요청에 집중했습니다.

#### [Server B]
- ProcessController 단에서 AtomicInteger를 활용한 MAX_IN_FLIGHT 임계치 제한으로 시스템 붕괴를 1차 방어합니다.
- Server B와 Server C 사이에 Redis Stream 기반의 비동기 메시지 큐(queue:to_server_c)를 배치하여, Server C의 처리 능력에 맞춰 유량을 조절(Pull 기반)하는 Backpressure 파이프라인을 구축했습니다.

#### Circuit Breaker 도입 (Resilience4j) - 시스템 붕괴 방어의 핵심
- 통신 구간(Server A ↔ Server B ↔ Server C ↔ PG사) 전체에 Resilience4j 기반 서킷 브레이커를 적용하여 장애를 격리했습니다.
- **Server C ➡️ 외부 PG사 (2단계 방어 적용):**
    - [1단계: 서킷 브레이커 Fail-Fast] 외부 PG사 응답 지연으로 에러율이 치솟으면 서킷이 개방(Open)됩니다. DB 커넥션을 낭비하지 않고 즉시 결제 시도를 차단한 뒤 전체 FAIL 처리하여 Server A의 재고 롤백을 유도합니다. (결제 시도 자체가 차단되었으므로 망취소 생략)
    - [2단계: DB 장애 시 망취소] 만약 정상적으로 PG 결제가 승인(결제 완료)되었으나 직후 Server C의 DB가 뻗어 상태 업데이트에 실패할 경우, 즉각 PG사 결제 취소(망취소) API를 호출하는 보상 트랜잭션을 실행해 고객 피해를 방지했습니다.
- **Server B ➡️ Server A (보상 트랜잭션):** Server A가 다운되어 재고 롤백 요청이 실패할 경우, 서킷 브레이커의 Fallback이 작동하여 해당 데이터를 즉시 Redis 재시도 큐(Retry Queue)로 격리합니다. 이후 좀비 워커 스레드가 추적하여 최종 일관성을 맞춥니다.
- **Server A ➡️ Server B (아웃박스 릴레이):** Server B가 응답하지 않을 때, Outbox 데이터를 강제로 밀어 넣지 않고 Fallback을 통해 발송을 중단합니다. 데이터는 RDBMS에 안전하게 보관되며, Server B가 복구(Circuit Close)되면 다음 스케줄러가 누락 없이 재전송합니다.

# 🚀 [Weverse] 선착순 프로모션 시스템 부하 테스트 및 성능 개선 보고서

## 1. 테스트 개요 및 환경 (Test Environment & Tools)
대규모 트래픽이 일시적으로 집중되는 '선착순 프로모션 굿즈 구매' 시나리오를 가정하여, 시스템의 안정성 및 최대 처리량을 검증하고 병목 구간을 개선하기 위해 진행되었습니다.

- **테스트 대상:** Server A (API Gateway & Promotion Service)
- **인프라 환경:** Docker Container 기반 단일 노드 (제한: 1vCPU, 2GB Memory)
- **사용 도구 (Tools):** k6 (부하 발생 및 지표 수집), Docker Compose
- **부하 시나리오 (Spike Test):**
    - 가상 사용자 (VUs): 1,000명 동시 접속
    - 목표 재고: 100개
    - 요청 조건: 20초간 지속적인 구매 요청 발생 (1인당 100건, 총 100,000건 집중 발송)
- **검증 목표:** 데이터 유실 없는 안정적인 비동기 처리 확인 및 단일 노드 성능 극대화

---

## 2. 단계별 성능 개선 과정 및 지표 분석

### 🔴 V1: 초기 동기식 아키텍처 (문제 도출)
요청 수신 후 DB Outbox 저장까지 완료한 뒤 응답을 반환하는 동기식 구조에서의 베이스라인 지표입니다.

- **결과:** 총 2,061건 처리 (성공률 100%)
- **지표:** 60.34 TPS / 평균 응답 속도 13.84s (최대 18.29s)
- **분석:** 긴 응답 시간으로 인해 Tomcat 스레드가 장시간 점유되며 심각한 병목이 발생했습니다.

### 🟡 V2: 완전 비동기 아키텍처 및 신뢰성 확보
V1의 병목을 해소하기 위해 애플리케이션 구조를 대대적으로 개편했습니다.

- **개선 사항:**
    - **비동기 즉시 응답:** API 요청 수신 시 ArrayBlockingQueue에 데이터를 적재한 직후 즉시 HTTP 202 (Accepted) 응답을 반환하여 톰캣 스레드 풀 고갈을 원천 차단했습니다.
    - **벌크 인서트 (Batch Insert):** JDBC URL에 rewriteBatchedStatements=true 옵션을 적용하고 다중 INSERT 문을 단일 쿼리로 결합하여 네트워크 I/O 오버헤드를 최소화했습니다.
- **지표:** 379.64 TPS / 평균 응답 속도 2.33s (최소 3.07ms)
- **분석:** 최소 응답 시간 3ms 달성으로 코어 로직 지연이 완벽히 해소되었으며, TPS가 약 6.3배 수직 상승했습니다.

### 🟢 V3: 다중 서버 확장을 고려한 B+Tree 인덱스 최적화
- **개선 사항:** 스케줄러가 DB에 데이터를 밀어 넣기 전, 메모리 레벨에서 trace_id (Unique Key) 기준으로 오름차순 정렬(Sort) 후 Insert를 수행하도록 변경했습니다.
- **효과:** 다중 인스턴스로 Scale-out 시 발생할 수 있는 Index Gap Lock 충돌(Deadlock)을 방지했습니다. 또한, 무작위 UUID 삽입으로 인한 B+Tree 페이지 분할 오버헤드를 방지했습니다.
- **지표:** 463.05 TPS / 평균 응답 시간 1.9s (V2 대비 22% 추가 상승)

### 🔬 [심층 분석] 1vCPU 환경의 병목 탐색과 의사결정 과정 (V4 ~ V6)
더 높은 성능을 끌어내기 위해 여러 최적화 기법을 실험했으나, 오히려 성능이 하락하는 현상을 통해 시스템의 진짜 병목을 정확히 식별해 냈습니다.

- 격리 수준 및 HTTP 커넥션 풀 조정 (V4, V5 탈락): 트랜잭션 격리 수준을 낮추고 HTTP Keep-Alive 커넥션 풀을 설정하여 성능 튜닝을 시도했으나, 유의미한 TPS 상승으로 이어지지 않았습니다.
- Tomcat 스레드 풀 튜닝 한계와 의사결정 (V7 탈락):
    - 1vCPU 자원 제약 하에서 Tomcat의 컨텍스트 스위칭 오버헤드를 최소화하기 위해 max-threads를 기본 200개에서 20개, 50개로 하향 조정했습니다. 이론상 CPU-bound에 가까워진 비동기 환경에서는 스레드가 적은 것이 유리할 것으로 가설을 세웠으나, 최고점 대비 성능이 하락(340~400 TPS)했습니다.
    - 분석 결과, 큐에 데이터를 적재하는 짧은 순간에도 I/O Wait이 발생하며 이 찰나의 대기 시간 동안 CPU가 유휴(Idle) 상태에 빠지는것이 원인이었습니다. 결론적으로 현재 아키텍처에서는 기본 스레드 풀(200개)이 I/O 대기 시간을 가장 효율적으로 커버함을 데이터로 증명하고 설정을 롤백했습니다.

### 🚀 V8: 가상 스레드 (Virtual Threads) 도입 및 최종 성능 검증
I/O 블로킹 구간의 근본적 해결을 위해 Java 21의 가상 스레드를 도입했습니다.

- **지표 상승:** 630 TPS
- **부하 테스트 인사이트 :**
    - **스레드 병목 해소:** 기존 플랫폼 스레드(OS Thread) 모델에서는 대규모 요청 인입 시 컨텍스트 스위칭(Context Switching) 오버헤드와 I/O 블로킹으로 인해 심각한 병목이 발생했습니다. 가상 스레드 도입을 통해 I/O를 논블로킹으로 전환해 제한된 자원 내에서도 스레드 효율을 높였습니다.

### 🚀 V9: 시스템 웜업(Warm-up)에 따른 지표 왜곡
동일한 조건으로 새로운 굿즈를 만든 후 서버를 재시작 하지 않고 선착순 API 수신 부하 테스트를 연속 수행한 결과, TPS가 630에서 2,698로 급증하는 현상을 확인했습니다. 이는 성능 개선이 아닌, 단일 코어(1vCPU) 환경에서 JVM 웜업 상태에 따른 전형적인 지표 왜곡 현상입니다.

- **콜드 스타트 (초기 테스트 - 630 TPS):** 초기 부하 인입 시, JVM의 JIT 컴파일러가 바이트코드를 기계어로 번역하고 Tomcat 스레드 풀 및 Jackson JSON 파서를 초기화하는 과정에서 1vCPU 자원의 심각한 병목이 발생했습니다. 이 '엔진 예열' 오버헤드로 인해 API Gateway의 초기 수신 속도는 630 TPS로 제한되었습니다.
- **웜업 완료 (연속 테스트 - 2,698 TPS):** 시스템 웜업이 완료된 후 서버 A는 순수 메모리 연산(ArrayBlockingQueue.offer)만으로 초당 2,698건의 스파이크 트래픽을 지연 없이 흡수하여 202 Accepted 응답을 반환했습니다.

---

## 3. 최종 부하 테스트 결과 요약
시스템 웜업(Warm-up)이 완료된 상태에서 성능을 측정한 최종 결과입니다.

### 📊 테스트 환경 및 조건
- 대상: Server A → B → C 통합 부하 테스트 (각 서버 1vCPU / 2GB Memory)
- 시나리오: 가상 유저(VU) 1,000명, 20초간 지속적인 트래픽 인입
- 시스템 상태: JIT 컴파일 및 커넥션 풀 예열이 완료된 웜업(Warm-up) 상태

### 📈 k6 측정 지표 (Metrics)
- 총 처리 요청 수 (Total Requests): 54,887건
- 성공률 (Success Rate): 99.99% (HTTP 202 Accepted 54,883건 성공)
- 최대 수신 처리량 (Throughput): 2,698.07 TPS
- 응답 속도 (Latency):
    - 평균 (avg): 260.05ms
    - 최소 (min): 15.98ms
    - p(95): 588.16ms

### 💡 테스트 인사이트
지표 분석 결과, Server A는 예열이 완료된 상태에서 HTTP 요청을 받아 메모리 큐(ArrayBlockingQueue)에 적재하고 즉시 응답하는 순수 수신 로직만을 통해 초당 약 2,700건의 스파이크 트래픽을 지연 없이 흡수했습니다. 이는 본 비동기 큐 아키텍처가 1vCPU라는 극단적인 자원 제약 속에서도 시스템 붕괴 없이 대규모 트래픽을 빨아들이는 강력한 충격 흡수 장치로 동작함을 데이터로 증명합니다.

---

## 인프라 확장(Scaling) 계획 및 계산 근거
현재 구축된 시스템은 Server A, B, C의 로직이 모두 연동된 E2E(End-to-End) 통합 부하 테스트를 통해, 단일 노드(1vCPU, 2GB) 환경에서 최대 630 TPS의 유효 결제 처리량을 검증했습니다. 이를 바탕으로 실제 100,000명의 동시 접속(CCU)을 수용하기 위한 인프라 스케일링 계획을 다음과 같이 산정했습니다.

### 1. 목표 시스템 트래픽(Target TPS) 도출
선착순 프로모션의 특성상 트래픽이 이벤트 오픈 직후 극초반에 쏠리는 현상(파레토 법칙)을 반영하여, 시스템이 방어해야 할 초당 최대 요청 수(TPS)를 역산합니다.

- 트래픽 집중도 가정: 이벤트 오픈 직후 최초 10초(Time Window) 이내에 대기열 접속자의 80%가 결제 API를 집중적으로 호출한다고 가정합니다.
- 초당 평균 요청 수: (100,000명 × 80%) / 10초 = 8,000 TPS
- 안전 마진 (Safety Margin): 예상치 못한 스파이크성 트래픽 및 네트워크 지연을 대비해 25%의 버퍼(Buffer)를 추가합니다.
- 최종 시스템 목표 처리량: 8,000 × 1.25 = 10,000 TPS

### 2. 총 필요 서버 인스턴스 산정
본 아키텍처는 하나의 인스턴스(단일 노드)가 전체 구매 파이프라인을 통합 처리하여 630 TPS를 소화하는 구조입니다. 따라서 이 통합 노드를 통째로 수평 확장(Scale-out)하는 방식으로 계산합니다.

- 기본 필요 대수: 10,000 TPS / 630 TPS = 15.87대
- 고가용성(HA) 확보: 특정 노드 장애(Fail-over) 시 남은 노드들이 무리 없이 트래픽을 감당할 수 있도록 여유율(약 20%)을 추가 확보합니다. 15.87대 × 1.2 = 19.04대
- 🎯 최종 산정 결과: 10만 명 동시 접속(10,000 TPS) 환경을 에러율 안정적으로 운영하기 위해 총 20대의 서버 인스턴스(1vCPU, 2GB 기준) 가 필요합니다.
  참고: 본 시스템은 품절 이후 단순 트래픽 방어력이 2,698 TPS에 달하므로, 20대가 구성될 경우 재고 소진 후에는 이론상 50,000 TPS 이상의 잉여 트래픽을 에러 없이 쳐낼 수 있는 압도적인 가용성을 확보하게 됩니다.

### 3. 인프라 확장 시 병목 방어 및 고도화 계획
위의 벤치마크 결과를 바탕으로, 실제 100,000명의 동시 접속자(CCU)가 몰리는 선착순 프로모션을 에러율 0%로 운영하기 위한 인프라 스케일링 계획입니다.

#### ① 목표 시스템 트래픽(Target TPS) 도출
선착순 이벤트 특성상 트래픽이 이벤트 오픈 직후 극초반에 쏠리는 파레토 법칙을 반영하여 시스템이 방어해야 할 순간 최대 부하(Spike)를 역산합니다.

- 트래픽 집중도 가정: 이벤트 오픈 직후 최초 10초 이내에 전체 접속자의 80%가 구매 API를 집중 호출한다고 가정.
- 초당 평균 요청 수: (100,000명 × 80%) / 10초 = 8,000 TPS
- 안전 마진: 예상치 못한 트래픽 스파이크 및 네트워크 지연을 대비해 25%의 버퍼 추가.
- 최종 시스템 목표 처리량: 8,000 × 1.25 = 10,000 TPS

#### ② 필요 서버 인스턴스 산정
선착순 이벤트는 오픈 정각(예: 12시 00분)에 가장 취약한 콜드 스타트(Cold Start) 상태에서 엄청난 트래픽을 맞이합니다. 따라서 인프라 사이징은 웜업 후의 최대 성능(2,698 TPS)이 아닌, 초기 JIT 컴파일 및 커넥션 생성 오버헤드가 동반된 가장 보수적인 지표(630 TPS)를 기준으로 산정하여 장애 리스크를 원천 차단합니다.

- 기본 필요 대수: 10,000 TPS / 630 TPS = 15.87대
- 고가용성(HA) 버퍼: 특정 노드 장애(Fail-over) 시 남은 노드들이 감당할 수 있도록 여유율 20% 추가 확보. (15.87대 × 1.2 = 19.04대)
- 🎯 최종 산정 결과: 10만 명 동시 접속(10,000 TPS) 환경을 안정적으로 운영하기 위해 총 20대의 Server A 인스턴스 (1vCPU 기준)가 필요합니다.
- 🚀 시스템 방어력 (Headroom): 20대로 구성된 클러스터는 오픈 후 수 초 내에 웜업이 완료되면, 노드당 2,600 TPS 이상의 성능을 발휘하여 최대 총합 50,000 TPS 이상의 폭발적인 트래픽을 메모리 큐로 흡수해 내는 압도적인 맷집을 확보하게 됩니다.

#### ③ 인프라 확장 시 예상 병목 및 고도화 계획
클러스터(서버 20대)가 초당 1만 건 이상의 트래픽을 성공적으로 수신하더라도, 이를 뒷단에서 처리하는 RDBMS에 부하가 집중되면 시스템 전체가 마비됩니다. 이를 완벽히 해소하기 위해 다음과 같은 아키텍처 고도화를 병행해야 합니다.

- DB 커넥션 풀 고갈 방어: 20대의 서버가 동시다발적으로 DB와 연결을 시도하여 리소스가 고갈되는 것을 막기 위해, 애플리케이션과 DB 사이에 ProxySQL 또는 PgBouncer 같은 Connection Pooler를 배치하여 커넥션을 다중화합니다.
- 재고 DB 락(Row Lock) 경합 해소: 앞단에서 큐잉된 데이터가 백그라운드 Worker를 통해 일정한 유량으로 DB에 도달하더라도, 단일 레코드에 대한 Update 경합은 여전히 존재합니다. 이를 원천 해결하기 위해 DB 앞단에 Redis 기반의 재고 차감 큐를 도입하여, 실시간 차감은 메모리에서 단일 스레드로 처리하고 RDBMS에는 최종 결과만 비동기로 반영하는 구조 고도화가 필수적입니다.
