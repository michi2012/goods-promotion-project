# 계획서: OutboxProcessor 중복 SELECT 제거

- 작성일: 2026-05-21

## 목표
`processSingleItem`과 `markAsFailDirectly`가 외부에서 이미 조회한 `RequestOutbox` 엔티티를 ID만 넘겨받아 내부에서 다시 `findById()`로 재조회하는 중복 SELECT를 제거한다.

## 성공 기준
- [ ] `processSingleItem(Long)` → `processSingleItem(RequestOutbox)` 시그니처 변경 및 내부 `findById()` 제거
- [ ] `markAsFailDirectly(Long)` → `markAsFailDirectly(RequestOutbox)` 시그니처 변경 및 내부 `findById()` 제거, `save()` 명시 추가
- [ ] `PromotionService`의 두 호출부가 엔티티를 직접 전달하도록 수정
- [ ] `OutboxProcessorTest`의 `findById` mock 제거, 엔티티 직접 전달로 테스트 수정
- [ ] `gradlew.bat :serverA:test` 전체 통과

## 비범위 (Out of Scope)
- `processSingleItem` 내부 비즈니스 로직 변경 없음
- `REQUIRES_NEW` 트랜잭션 구조 변경 없음
- `markAsFailDirectly` 외의 bulk화 작업

## 단계별 작업 계획

### 단계 1: OutboxProcessor 시그니처 변경
- 변경 파일: `serverA/src/main/java/weverse/serverA/service/outbox/OutboxProcessor.java`
- 변경 내용:
  - `processSingleItem(Long outboxId)` → `processSingleItem(RequestOutbox outbox)`, 내부 `findById()` 제거
  - `markAsFailDirectly(Long outboxId)` → `markAsFailDirectly(RequestOutbox outbox)`, `findById().ifPresent` 제거 후 `outbox.markAsFail(); outboxRepository.save(outbox);`로 대체
- 검증 방법: `gradlew.bat :serverA:compileJava`
- 롤백 방법: git checkout 해당 파일
- 예상 소요: 짧음

### 단계 2: PromotionService 호출부 변경
- 변경 파일: `serverA/src/main/java/weverse/serverA/service/PromotionService.java`
- 변경 내용: `processSingleItem(outbox.getId())` → `processSingleItem(outbox)`, `markAsFailDirectly(outbox.getId())` → `markAsFailDirectly(outbox)`
- 검증 방법: `gradlew.bat :serverA:compileJava`
- 롤백 방법: git checkout 해당 파일
- 예상 소요: 짧음

### 단계 3: 테스트 수정 및 전체 통과 확인
- 변경 파일: `serverA/src/test/java/weverse/serverA/service/outbox/OutboxProcessorTest.java`
- 변경 내용: `findById` mock 제거, `processSingleItem(1L)` → `processSingleItem(outbox)` 직접 전달
- 검증 방법: `gradlew.bat :serverA:test`
- 롤백 방법: git checkout 해당 파일
- 예상 소요: 짧음

## 리스크 및 대응
- detached 엔티티 전달: `processSingleItem`은 이미 `save()`를 명시 호출하므로 `merge()` 발생 → 문제 없음. `markAsFailDirectly`도 `save()` 추가로 동일하게 처리.
