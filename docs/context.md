# 맥락 노트: PR #55 리뷰 반영 (BL-041)

## [완료] BL-041 (PR #56)

### 왜 이 방식을 선택했는가
- PR #55는 리뷰 없이 머지됐고(사용자 결정), 머지 후 `/pr-review 55`로 [필수] 1·[제안] 2가 나왔다. 머지된 PR이라 skill 5단계대로 백로그 등록 후 새 브랜치·별도 PR로 반영한다.
- **C1 [필수]**: P-12(환불 후 재구매 불가, 2026-11-14 결정 "구매 기회 소멸")를 "구매 표시를 풀지 않음"으로 구현했지만 표시(`user:purchase:{userId}:{goodsId}`)는 TTL 1시간이라 1시간 뒤 환불이면 재구매가 열린다. 사용자와 정책은 유지하기로 했으므로 **환불 완료 시 표시를 만료 없이 다시 쓴다**. 명세에 "1시간 이내" 한계를 적는 안은 정책을 약하게 바꾸는 것이라 채택 안 함.
- **C2 [제안]**: 롤링 배포 중 옛 파드가 `paid_at` 없이 PAID를 만들면 `getPaidAt()` NPE → 500 반복. V4 backfill과 같은 근거로 `updatedAt` 대체.
- **C3 [제안]**: Redis `releaseStock`이 커밋 전이라 이후 DB 실패 → Kafka 재시도 시 Redis가 두 번 오름. 사용자 결정(2026-11-16) A안: Saga `markFailedAndCheck`처럼 **방어 키 SETNX**로 Redis 증가를 주문당 1회로.
- 미리보기 형식: 리뷰 미리보기를 요약해서 보여줘 이해가 어려웠다(SKILL.md 3단계는 "코멘트 전문"을 요구). 같은 PR에서 형식을 구체화 (사용자 지시).

### 검토했으나 채택하지 않은 대안
#### C1 — 구매 단계 DB 중복 확인
- 왜 안 썼나: 선착순 구매 경로에 DB 조회가 들어가 Redis만으로 막는 성능 설계와 충돌. 1인 1회 정책 전반과 함께 BL-042에서 검토.
#### C3 — Redis 복구를 커밋 후(`afterCommit`)
- 왜 안 썼나: 커밋 뒤 Redis 실패는 재시도가 없어 Redis 재고가 덜 복구된 채 남는다. 방어 키는 재시도로 회복되고 Saga 패턴과 일관.
#### C2 — V5로 backfill 재실행
- 왜 안 썼나: 배포 순서에 의존하고, 배포 중 생긴 주문을 다시 놓칠 수 있다. 코드 대체는 항상 동작.

### 기존 코드베이스 컨벤션
- Redis 재시도 방어: `SagaStateService.markFailedAndCheck` (increment 결과로 첫 처리 판별)
- Redis 키·TTL: `RedisStockService` (`user:purchase:*` 1시간, `goods:stock:*` 무기한), design-notes "2단계 — Redis SETNX"
- 테스트: `RedisStockServiceTest`(AbstractSpringBootTest — Testcontainers), `RefundServiceTest`(Mockito, TransactionTemplate 콜백 실행)

### 관련 파일/위치
- `serverA/src/main/java/promotion/serverA/service/RedisStockService.java`
- `serverA/src/main/java/promotion/serverA/service/RefundService.java` (`completeRefund`, 기간 판단)
- `.claude/skills/pr-review/SKILL.md` 3단계
- PR #55 코멘트 id: C1 4582692038, C2 4582692046, C3 4582692049
