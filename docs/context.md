# 맥락 노트: serverA 계약 테스트 동작시키기

## 왜 이 방식을 선택했는가
`OpenApiContractTest`(#18 추가)는 serverA가 노출하는 `/v3/api-docs`를 계약 원본 `contracts/server-a-openapi.yaml`과 비교하는 drift-check다. 그런데 두 겹으로 항상 실패하고 있었다(main에서도 동일, worktree로 확인).
1. serverA에 springdoc 의존성이 없어 `/v3/api-docs` 자체가 없다 → 500.
2. 테스트가 그냥 `@SpringBootTest`라 실제 DB 설정을 그대로 쓴다. 로컬은 `localhost:3306/order`(없음), CI는 `SPRING_DATASOURCE_URL=jdbc:h2:mem`인데 `driver-class-name`이 MySQL로 고정 → 컨텍스트 로드 실패.

결정 (2026-11-13):
- springdoc은 **`webmvc-api` 2.8.5** (사용자 선택). 계약 테스트에 필요한 건 `/v3/api-docs`뿐이고, Swagger UI를 추가로 노출할 이유가 없다. 버전은 같은 Boot 3.5.14에서 동작 중인 serverB와 맞춘다.
- DB 문제는 **테스트가 기존 `AbstractSpringBootTest`를 상속**하게 해서 푼다. `@DynamicPropertySource`가 환경변수보다 우선하므로 CI의 H2 URL도 Testcontainers MySQL로 덮인다. `ServerAApplicationTests`가 이미 같은 방식으로 통과 중이다.

## 검토했으나 채택하지 않은 대안
### 대안 A: `webmvc-ui` (serverB와 동일)
- 왜 안 썼나: Swagger UI 정적 리소스·`/swagger-ui` 노출이 계약 테스트에 불필요. 사용자가 api 모듈 선택.

### 대안 B: `driver-class-name` 제거 + H2 테스트 의존성
- 왜 안 썼나: 컨텍스트에 Redis·Kafka도 필요하고, Flyway MySQL 마이그레이션(`MODIFY COLUMN`, 다중 `ADD COLUMN`)의 H2 호환을 따로 맞춰야 한다. 기존 Testcontainers 베이스 재사용이 단순.

### 대안 C: 계약 테스트 삭제·비활성화
- 왜 안 썼나: contract-first 구조의 유일한 백엔드 drift-check.

## 기존 코드베이스 컨벤션
- 통합 테스트 베이스: `serverA/src/test/java/promotion/serverA/support/AbstractSpringBootTest.java` (Singleton Container 패턴)
- 계약 테스트: `serverA/.../contract/OpenApiContractTest.java`, `serverB/.../contract/OpenApiContractTest.java`
- springdoc 사용 예: `serverB/build.gradle`

## 관련 파일/위치
- `serverA/build.gradle` — 의존성
- `serverA/src/test/java/promotion/serverA/contract/OpenApiContractTest.java` — 계약 테스트
- `contracts/server-a-openapi.yaml` — 계약 원본
- `.github/workflows/ci-cd.yaml` — CI (H2 환경변수, `./gradlew clean test`)

## 외부 참조
- springdoc-openapi 2.x (Spring Boot 3)
