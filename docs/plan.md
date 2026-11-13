# 계획서: serverA 계약 테스트(OpenApiContractTest) 동작시키기

- 작성일: 2026-11-13
- 관련 이슈/티켓: 없음 (결제 금액 기본기 최종 검증 중 발견 — main에서도 항상 실패)
- 브랜치: `feature/payment-amount` (후속 작업)

## 목표
serverA의 계약 drift-check(`OpenApiContractTest`)가 실제로 실행되어, 구현 스펙(`/v3/api-docs`)과 `contracts/server-a-openapi.yaml`의 불일치를 잡도록 한다.

## 성공 기준
- [ ] serverA가 `/v3/api-docs`를 제공한다 (`springdoc-openapi-starter-webmvc-api:2.8.5`, 사용자 승인 완료)
- [ ] `OpenApiContractTest` 2건 통과 (`gradlew :serverA:test --tests OpenApiContractTest`)
- [ ] CI와 같은 환경변수(`SPRING_DATASOURCE_URL=jdbc:h2:mem:...`)로 실행해도 통과 (Testcontainers MySQL로 덮어쓰기 확인)
- [ ] serverA 전체 테스트 82/82 통과 (`gradlew :serverA:test`)

## 비범위 (Out of Scope)
- CI 워크플로(`.github/workflows/ci-cd.yaml`)의 H2 환경변수 정리
- `application.yaml`의 `driver-class-name` 변경
- 운영 프로파일에서 `/v3/api-docs` 비활성화
- Swagger UI 도입
- 계약 비교 로직(필드명 집합 비교) 강화

## 단계별 작업 계획

### 단계 1: springdoc 의존성 추가 ⚠️ 새 의존성 (승인 완료)
- 변경 파일: `serverA/build.gradle`
- 변경 내용 요약: `implementation 'org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.5'` 추가 (serverB와 같은 버전, UI 없는 api 모듈).
- 검증 방법: `.\gradlew.bat :serverA:compileJava` + 단계 2 테스트에서 `/v3/api-docs` 응답 확인
- 롤백 방법: `git checkout -- serverA/build.gradle`
- 예상 소요: 짧음

### 단계 2: 계약 테스트를 공용 통합 테스트 베이스에 연결
- 변경 파일: `serverA/src/test/java/promotion/serverA/contract/OpenApiContractTest.java`
- 변경 내용 요약: `AbstractSpringBootTest`(Testcontainers MySQL·Redis + EmbeddedKafka + `@DynamicPropertySource`)를 상속. 기존 `@SpringBootTest(webEnvironment = RANDOM_PORT)`와 비교 로직은 유지.
- 검증 방법: `.\gradlew.bat :serverA:test --tests "promotion.serverA.contract.OpenApiContractTest"`, 이어서 CI 환경변수(H2 URL)를 설정한 채 같은 명령 재실행
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 짧음

### 단계 3: 전체 회귀 + 문서
- 변경 파일: `docs/arch-snapshot.md`
- 변경 내용 요약: serverA 전체 테스트 실행. arch-snapshot에 serverA `/v3/api-docs`(springdoc api) 및 계약 테스트 동작 반영.
- 검증 방법: `.\gradlew.bat :serverA:test` 82/82
- 롤백 방법: `git checkout --` 해당 파일
- 예상 소요: 보통

## 리스크 및 대응
- 리스크: springdoc 2.8.5와 Boot 3.5.14 호환성 → 대응: serverB가 같은 조합으로 동작 중
- 리스크: 하위 클래스의 `@SpringBootTest(webEnvironment = RANDOM_PORT)`가 베이스의 `@SpringBootTest`와 다른 컨텍스트를 만들어 테스트 시간이 늘어남 → 대응: 컨텍스트 1개 추가 수준, 허용
- 리스크: 계약 테스트가 실제로 불일치를 찾아 실패 → 대응: 계약을 고칠지 구현을 고칠지는 사용자 판단 (테스트 주석 원칙), 발견 시 정지 후 보고

## 의존성
- Docker (Testcontainers)
- 신규 Gradle 의존성: `org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.5` (사용자 승인 2026-11-13)
