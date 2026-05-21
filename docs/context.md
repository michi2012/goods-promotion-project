# 맥락 노트: OutboxProcessor 중복 SELECT 제거

## 왜 이 방식을 선택했는가
`processPendingRequests()`는 `findByStatus()`로 최대 500건의 `RequestOutbox`를 한 번에 조회한다.
그런데 루프 안에서 `processSingleItem(outbox.getId())`와 `markAsFailDirectly(outbox.getId())`가 ID만 받아서 내부에서 `findById()`를 다시 호출하고 있었다.
이미 메모리에 있는 데이터를 DB에 500번 재요청하는 구조이므로, 시그니처를 `RequestOutbox`를 직접 받도록 변경하여 중복 SELECT를 제거했다.

## 검토했으나 채택하지 않은 대안
### 대안 A: 시그니처 유지, findById() 제거 후 파라미터 재활용
- 무엇: 메서드 내부에서 전달받은 Long ID 대신 외부 엔티티를 그냥 써버리는 방식
- 왜 안 썼나: 시그니처가 Long이면 호출자가 엔티티를 갖고 있어도 ID를 꺼내서 넘겨야 하므로 의도가 불명확해짐. 타입으로 의도를 표현하는 것이 낫다.

### 대안 B: 유지
- 무엇: 현상 유지
- 왜 안 썼나: 500건 처리 시 불필요한 DB 왕복 500회 발생. 부하 테스트에서 실측된 병목.

## detached 엔티티 처리
`REQUIRES_NEW`로 새 트랜잭션이 열리면 외부에서 넘어온 엔티티는 detached 상태다.
- `processSingleItem`: 이미 `outboxRepository.save(outbox)` 명시 호출이 있어 merge()로 처리됨 → 문제 없음
- `markAsFailDirectly`: 기존에는 `findById().ifPresent()` 패턴으로 managed 엔티티를 dirty checking으로 저장했으나, `findById()` 제거 후 `save()` 명시 추가로 동일하게 처리

## 관련 파일/위치
- `serverA/.../service/outbox/OutboxProcessor.java` — 시그니처 변경 및 findById() 제거
- `serverA/.../service/PromotionService.java` — 호출부 변경
- `serverA/.../service/outbox/OutboxProcessorTest.java` — findById mock 제거, 엔티티 직접 전달
