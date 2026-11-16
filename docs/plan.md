# 계획서: Kafka DLT 발행 파티션 고정 해제 (BL-037)

- 작성일: 2026-11-16
- 관련 이슈/티켓: BL-037 (`docs/backlog.md`) — BL-029 PR②-b 단계 5 착수 중 발견

## [진행 중] BL-037

### 목표
serverA·B·C의 공통 에러 핸들러가 재시도를 다 쓴 레코드를 원본과 같은 파티션 번호로 DLT에 발행해, 파티션 1개짜리 DLT 토픽에 원본 파티션 1·2의 레코드가 도달하지 못하는 문제를 고친다.

### 성공 기준
- [ ] 세 서버 모두 원본 파티션 2인 레코드의 DLT 발행이 `{원본}.DLT` 토픽, 파티션 미지정(`null`)으로 나간다 (서버별 `KafkaConsumerConfigTest`)
- [ ] 세 서버 기존 테스트 통과 (`:serverA:build :serverB:build :serverC:build`)
- [ ] 로컬 백엔드 E2E 회귀 통과 (server-a/b/c 이미지 재빌드 후 `backend-e2e/ npm test`)

### 비범위 (Out of Scope)
- DLT 토픽 파티션 수 변경 (방식 B — 채택 안 함)
- serverC DLT 리스너 팩토리(BL-034 처리분)·serverA·B DLT 리스너 팩토리의 백오프 (BL-035)
- BL-029 PR②-b (stash 보관 중, 이 PR 머지 후 재개)

### 단계별 작업 계획

#### 단계 1: DLT 대상 파티션 -1 + 서버별 테스트
- 변경 파일: `serverA|serverB|serverC/src/main/java/promotion/{서버}/config/KafkaConsumerConfig.java`, 테스트 `serverA|serverB/src/test/.../config/KafkaConsumerConfigTest.java`(신규), `serverC/src/test/.../config/KafkaConsumerConfigTest.java`(기존 — 테스트 추가)
- 변경 내용: recoverer의 대상 `TopicPartition(record.topic() + ".DLT", -1)` — 음수면 spring-kafka 3.3이 파티션을 지정하지 않고 프로듀서에 맡긴다. 원본 위치는 DLT 헤더(`kafka_dlt-original-partition`)에 남는다. 테스트는 `errorHandler()` recoverer에 파티션 2 레코드를 넣고 mock `KafkaTemplate`로 `ProducerRecord`를 가로채 토픽·파티션을 확인한다.
- 검증: `.\gradlew.bat :serverA:test --tests "*KafkaConsumerConfigTest" :serverB:test --tests "*KafkaConsumerConfigTest" :serverC:test --tests "*KafkaConsumerConfigTest"`
- 롤백: `git checkout -- <파일>`, 신규 테스트 삭제
- 예상 소요: 짧음

#### 단계 2: 빌드 + 로컬 E2E 회귀
- 검증: `:serverA:build :serverB:build :serverC:build`(백그라운드) → server-a/b/c 이미지 재빌드(`up -d --build --no-deps server-a server-b server-c`) → `backend-e2e/ npm test`
- 주의: main 기준이라 serverA 이미지에 V4 마이그레이션은 들어가지 않는다 (②-b는 stash)
- 예상 소요: 보통

#### 단계 3: 문서
- 변경 파일: `docs/backlog.md`(BL-037 진행 중), 3종 문서 갱신
- 예상 소요: 짧음

### 리스크 및 대응
- 리스크: DLT 파티션 지정 해제로 DLT 레코드의 원본 파티션 정보가 사라진 것처럼 보임 → 대응: spring-kafka가 원본 토픽·파티션·오프셋을 DLT 헤더로 남긴다. DLT 소비 코드는 파티션 번호를 쓰지 않는다 (확인)
- 리스크: ②-b stash 복원 시 3종 문서 충돌 → 대응: 이 PR 머지 후 ②-b 브랜치에서 `git merge main` → `git stash pop` → 3종 문서는 ②-b 내용으로 정리
- 리스크: DLT로 가는 경로를 E2E로 재현하지 못함 → 대응: 단위 테스트로 발행 대상 고정, E2E는 정상 흐름 회귀만

### 의존성
- 새 의존성 없음 (spring-kafka 3.3.15)
