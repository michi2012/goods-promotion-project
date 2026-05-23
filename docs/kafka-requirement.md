- RequestOutbox 역할이 주문이니 Order 엔티티로 바꾸자

## 1. 아키텍처 개요

```
서버A (Orchestrator): 재고 선점(Edge) + 주문 생성 + Saga 지휘 (Command + Saga)
서버B (Query):        Redis 주문 상태 관리 + 유저 실시간 조회 API + 레디스 재고 조회 API (CQRS Read)
서버C (Payment):      결제 처리 + 최종 저장
```

서버A가 Saga Orchestrator 역할을 합니다.
서버B/C에 명령을 병렬 전송하고, 두 서버의 결과를 모두 비동기로 수신한 후에만 최종 상태를 확정합니다.
한쪽이라도 실패하면 전체 보상 트랜잭션을 실행합니다.

[서버A — Edge 역할]
├── 1차: 로컬 캐시 품절 체크 (Redis 안 감)
├── 2차: Redis SETNX 중복 유저 차단
├── 3차: Redis Lua Script 재고 차감 (원자적)
├── 실패 → 즉시 품절/중복 응답
├── 성공 → Kafka produce (동기 ACK 대기, 3초 타임아웃)
├── Kafka 실패 → Redis 재고 복구 + 유저 마킹 해제
└── 유저에게 "당첨" 응답
↓
[Kafka] — purchase-events 토픽 (파티션 3개)
↓
[서버A — Orchestrator 역할] (Kafka Consumer)
├── DB 주문 INSERT + DB 재고 차감 (@Transactional)
├── 소프트 홀드 설정 (10분 TTL)
├── Kafka → order-confirmed → 서버B에 레디스 데이터 업데이트 명령
├── Kafka → payment-request → 서버C에 결제 요청
├── Kafka ← payment-success/failed ← 서버C 결과 수신
│
├── [결제 성공 시]
│   ├── DB 주문 상태 → PAID
│   ├── Kafka → 서버B: Redis 주문 상태 PAID 업데이트
│   └── Kafka → 서버C: 최종 기록 저장
│
└── [결제 실패 시]
├── DB 주문 상태 → FAILED
├── Redis 재고 복구 + 유저 마킹 해제
└── Kafka → 서버B: Redis 주문 상태 FAILED 업데이트

[서버B — Query 서비스] (CQRS 읽기)
├── Kafka Consumer: 서버A 명령 수신
├── Redis 주문 상태 업데이트 (PENDING → PAID/FAILED)
└── 유저 실시간 주문 상태 조회 API 제공

[서버C — 결제 서비스]
├── Kafka Consumer: 결제 요청 수신
├── PG사 결제 처리
├── Kafka produce: 결제 결과 (성공/실패) → 서버A로
└── 결제 성공 시: 최종 결제 내역 저장


## 3. Saga 상태 흐름도

```
[purchase-events 수신] — Phase 1
    │
    ├── Step 1: DB 주문 INSERT + DB 재고 차감 + Saga 상태 생성 (한 @Transactional)
    │   ├── 실패 → Redis 재고 복구 + 유저 해제 → 끝
    │   └── 성공 ↓
    │
    ├── Step 2: 서버B에 order-status-update 전송 (PENDING)  ┐ 병렬 전송
    ├── Step 3: 서버C에 payment-request 전송               ┘ (비동기, 결과는 별도 토픽으로 수신)
    │
    ↓

[status-update-result 수신] — Phase 2a (서버B 결과)
    ├── 성공 → sagaState.statusUpdateCompleted = true → tryCompleteSaga()
    └── 실패 → handleSagaFailure() (전체 보상)

[payment-result 수신] — Phase 2b (서버C 결과)
    ├── 성공 → sagaState.paymentCompleted = true → tryCompleteSaga()
    └── 실패 → handleSagaFailure() (전체 보상)

[tryCompleteSaga] — Phase 3 (두 결과 합류)
    ├── 둘 다 완료? → 아니면 return (나머지 하나 대기)
    └── 둘 다 완료! → 최종 확정
        ├── DB 주문 상태 → PAID
        ├── 서버B에 order-status-update 전송 (PAID)
        └── 서버C에 order-completed 전송 (최종 저장)

[소프트 홀드 타임아웃] — 스케줄러 (1분마다)
    └── 10분 내 결과 미도착 → handleSagaFailure() (전체 보상 + EXPIRED)