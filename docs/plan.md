# 계획서: @EnableJpaAuditing 분리 — WebMvcTest 충돌 해소

- 작성일: 2026-05-21
- 관련 이슈: OrderControllerTest IllegalArgumentException (JPA metamodel must not be empty)

## 목표
`@EnableJpaAuditing`을 `ServerCApplication`에서 별도 `@Configuration` 클래스로 분리하여, `@WebMvcTest`가 JPA 없이 컨텍스트를 로드할 때 발생하는 충돌을 해소한다.

## 성공 기준
- [ ] `OrderControllerTest` 실행 시 `IllegalArgumentException: JPA metamodel must not be empty` 미발생
- [ ] `OrderControllerTest`의 `receiveBulkOrders_Success` 테스트 통과
- [ ] 프로덕션 애플리케이션 기동 시 JPA Auditing 정상 동작 (기존과 동일)

## 비범위 (Out of Scope)
- `OrderControllerTest` 테스트 로직 자체 수정
- 다른 서버(A, B)의 동일 패턴 수정 (별도 작업)
- JPA Auditing 설정 내용 변경

## 단계별 작업 계획

### 단계 1: JpaAuditingConfig 생성 및 @EnableJpaAuditing 이동
- 변경 파일:
  - `serverC/src/main/java/weverse/serverC/config/JpaAuditingConfig.java` (신규)
  - `serverC/src/main/java/weverse/serverC/ServerCApplication.java` (수정)
- 변경 내용 요약:
  - `ServerCApplication`에서 `@EnableJpaAuditing`과 관련 import 제거
  - `JpaAuditingConfig.java`에 `@Configuration @EnableJpaAuditing` 추가
- 검증 방법: `./gradlew :serverC:test --tests weverse.serverC.controller.OrderControllerTest`
- 롤백 방법: `JpaAuditingConfig.java` 삭제, `ServerCApplication.java`에 `@EnableJpaAuditing` 복원
- 예상 소요: 짧음

## 리스크 및 대응
- 리스크: `config` 패키지가 없을 경우 → 대응: 디렉토리는 파일 생성 시 자동 생성됨
- 리스크: serverA, serverB도 동일 패턴일 수 있음 → 대응: 이번 범위 외, 별도 확인

## 의존성
- `spring-data-jpa` 의존성 (이미 존재)
