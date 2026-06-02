# 맥락 노트: arch-snapshot 문서 정확성 수정 + Saga EXPIRED 메트릭 추가

## 왜 이 방식을 선택했는가
- `saga:hold` 키는 `a0635bd` 커밋에서 "사용하지 않는 Redis hold ttl 키"로 제거됨
- 3분 타임아웃 판단은 `saga:state:{orderId}` 해시의 `createdAt` 필드 기반으로 동작 (SagaTimeoutScheduler)
- `saga.expired.total` Counter는 태그 없이 단순 카운터로 구현 — 현재 단일 타임아웃 경로만 존재하므로 태그 불필요

## 검토했으나 채택하지 않은 대안
### saga:hold 행 TTL만 수정
- 무엇: 10분 → 3분으로만 바꾸는 방법
- 왜 안 썼나: saga:hold 키 자체가 코드에 존재하지 않아 문서가 더 부정확해짐

### MeterRegistry 대신 SimpleMeterRegistry 사용 (테스트)
- 무엇: 테스트에서 실제 SimpleMeterRegistry 인스턴스 주입
- 왜 안 썼나: 기존 테스트가 @InjectMocks/@Mock 패턴 일관 사용 중 — 패턴 통일

## 관련 파일/위치
- `docs/arch-snapshot.md` — 전체 아키텍처 스냅샷 문서
- `serverA/.../scheduler/SagaTimeoutScheduler.java` — saga:state:* SCAN, 3분 초과 EXPIRED 처리
- `serverA/.../service/SagaStateService.java` — saga:state 해시 초기화(createdAt 포함) 및 조회
