# testing — 이 프로젝트의 함정 기록

증상 → 원인 → 대처. (CLAUDE.md "재발 방지 기록")

## Spring Batch 테스트
- `@SpringBatchTest`를 붙이면 Job 테스트가 전부 `Could not create job execution from method: run` / `No matching arguments found` → 스코프 리스너(`JobScopeTestExecutionListener`)가 테스트 클래스에서 `JobExecution`을 돌려주는 메서드(예: `run(LocalDate)` 헬퍼)를 스코프용 팩토리로 오인해 호출 → `@SpringBatchTest` 대신 `@TestConfiguration`으로 `JobLauncherTestUtils`·`JobRepositoryTestUtils`를 직접 등록 (`serverC/.../batch/ReconciliationJobTestSupport.java`).
- 직접 등록한 `JobLauncherTestUtils`에서 `getJobLauncher()`가 null → 5.2에서는 setter 자동 주입이 안 됨 → `setJob`·`setJobLauncher`·`setJobRepository`를 명시적으로 호출.
- 슬라이스 구성: `@JdbcTest` + `@Transactional(NOT_SUPPORTED)`(JobRepository가 기존 트랜잭션을 거부) + `@ImportAutoConfiguration(BatchAutoConfiguration.class)`.
