---
name: backend
description: Spring Boot + JPA + MySQL 백엔드 작업 시 활성화. REST 컨트롤러, 서비스 레이어, JPA 엔티티, 리포지토리, 트랜잭션, 마이그레이션, 인증/인가, 외부 통합 작업. 사용자가 컨트롤러, 서비스, 엔티티, 리포지토리, JPA, 쿼리, API, 엔드포인트, DTO, 트랜잭션, 마이그레이션, Spring, MySQL 등을 언급하거나 src/main/java/, controller/, service/, repository/, entity/, domain/, dto/ 디렉토리 작업 시 자동 발동.
---

# Spring Boot + JPA + MySQL 백엔드 작업 매뉴얼

## 0. 작업 시작 전 필수 확인 사항

### 0-1. 영향 레이어 식별
- Controller만? → DTO/Validation까지?
- Service 레이어? → 트랜잭션 경계는 어디인가?
- Repository/JPA? → 쿼리 성능과 N+1 위험은?
- DB 스키마 변경? → 마이그레이션 필요 + 무중단 배포 영향?

### 0-2. 기존 패턴 확인 (필수)
새 코드 작성 전 반드시 다음을 먼저 읽는다:
- 유사한 기존 Controller 1개
- 유사한 기존 Service 1개
- 유사한 기존 Repository 1개
- `application.yml` 또는 `application.properties` 설정 (open-in-view, datasource, ddl-auto 등)

기존 명명 규칙, 패키지 구조, 응답 형식, 예외 처리 방식을 **그대로 따른다**. 새 패턴을 도입하지 마라.

### 0-3. 트랜잭션 경계 결정
- 여러 DB 작업이 원자성을 가져야 하는가? → Service 메서드에 @Transactional
- 외부 API 호출이 섞이는가? → 트랜잭션 외부로 분리 (긴 트랜잭션 방지)
- 읽기 전용인가? → @Transactional(readOnly = true)

---

## 1. 결정 트리 — 작업별 상세 챕터

| 작업 유형 | 참조 파일 |
|---|---|
| 신규 REST API 엔드포인트 | `api-pattern.md` |
| JPA 엔티티 / 리포지토리 / 쿼리 | `jpa-rules.md` |
| 트랜잭션 처리 | `transaction.md` |
| 인증 / 인가 (Spring Security) | `security.md` |
| DB 마이그레이션 (Flyway/Liquibase) | `migration.md` |
| 예외 처리 / 에러 응답 | `exception.md` |
| 테스트 작성 | `testing.md` |

해당 챕터가 없으면 아래 보편 규칙만 적용한다.

---

## 2. 보편 규칙 (모든 백엔드 작업에 적용)

### 2-1. 레이어 책임 분리
| 레이어 | 책임 | 금지 |
|---|---|---|
| Controller | HTTP ↔ DTO 변환, 검증, Service 호출, 응답 매핑 | 비즈니스 로직, JPA 엔티티 직접 노출, DB 호출 |
| Service | 비즈니스 로직, 트랜잭션 경계, 도메인 규칙 | HTTP 의존성 (HttpServletRequest 등), View 의존성 |
| Repository | DB 접근, 쿼리 | 비즈니스 로직 |
| Entity | 도메인 상태, 도메인 행위 | DTO 역할, 외부 노출 |
| DTO | 외부 인터페이스 표현 | 비즈니스 로직, JPA 어노테이션 |

### 2-2. 엔티티는 절대 외부에 노출하지 않는다
- Controller가 Entity를 반환하면 안 된다. 반드시 DTO로 변환.
- Request도 Entity가 아닌 별도 DTO로 받는다.
- 이유: 양방향 직렬화 무한루프, 지연 로딩 오류, 의도치 않은 필드 노출, API 계약과 DB 스키마 결합.

### 2-3. 입력 검증
- Controller 진입 시 `@Valid` + Bean Validation (`@NotNull`, `@NotBlank`, `@Size`, `@Email`, `@Pattern` 등) 사용.
- 도메인 규칙 검증은 Service에서.
- 검증 실패는 `@ControllerAdvice`로 일관된 응답 형식으로 변환.

### 2-4. 예외 처리
- 비즈니스 예외는 별도 예외 클래스(예: `UserNotFoundException`, `DuplicateEmailException`)로 정의.
- `@ControllerAdvice` + `@ExceptionHandler`로 전역 예외 처리.
- 클라이언트에 스택 트레이스 노출 절대 금지.
- `RuntimeException` 던지면 트랜잭션 롤백, `Checked Exception`은 기본적으로 롤백 안 됨 → `@Transactional(rollbackFor = ...)` 명시 또는 RuntimeException 사용.

### 2-5. 응답 형식
프로젝트의 기존 응답 래퍼가 있으면 그것을 따른다. 없다면 기본:

성공:
```json
{
  "success": true,
  "data": {}
}
```

실패:
```json
{
  "success": false,
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "사용자를 찾을 수 없습니다",
    "details": {}
  }
}
```

### 2-6. 로깅
- SLF4J + Logback 사용 (System.out.println 절대 금지).
- 로그 레벨 가이드:
    - `ERROR`: 예외, 시스템 장애
    - `WARN`: 비정상이지만 처리됨 (재시도 성공, 폴백 발동 등)
    - `INFO`: 주요 비즈니스 이벤트 (주문 생성, 결제 완료)
    - `DEBUG`: 개발 시 추적용
- **로깅 금지 정보**: 비밀번호, 토큰, 카드번호, 주민번호, 전체 요청/응답 본문.
- MDC를 사용해 요청 ID/사용자 ID를 컨텍스트에 포함.

### 2-7. 보안 체크리스트 (매 작업마다)
- [ ] 인증이 필요한 엔드포인트인가? Security 설정 확인했나?
- [ ] 인가(권한) 검사: 본인 데이터만 접근 가능한가? (IDOR 방어)
- [ ] 입력 검증: 타입뿐 아니라 비즈니스 규칙도 검증했나?
- [ ] SQL 인젝션: 모든 쿼리가 파라미터 바인딩을 사용하는가?
- [ ] 비밀 정보가 응답/로그에 노출되지 않는가?
- [ ] CORS 설정이 적절한가?
- [ ] CSRF: Stateless API라면 disable, 세션 기반이면 enable.

### 2-8. 절대 금지 패턴
- ❌ `application.yml`에 `spring.jpa.open-in-view: true` (기본값) 방치 → 반드시 `false`로
- ❌ `spring.jpa.hibernate.ddl-auto: update` 또는 `create` in production
- ❌ Entity 필드에 Lombok `@Data` (equals/hashCode 위험) → `@Getter` + 필요한 것만
- ❌ Entity에 `@AllArgsConstructor` 무분별 사용 → 정적 팩토리 메서드 권장
- ❌ Repository에서 `findAll()` 후 in-memory 필터링 (DB에서 처리)
- ❌ Controller에서 직접 `@Transactional`
- ❌ Service 메서드 내부에서 다른 Service 메서드 호출 시 `this.method()` (프록시 우회됨)

---

## 3. 테스트 요구사항

새 코드는 최소 다음 테스트를 작성한다:

| 레이어 | 테스트 종류 | 도구 |
|---|---|---|
| Controller | `@WebMvcTest` + MockMvc | MockMvc, Mockito |
| Service | 단위 테스트 (Mock Repository) | Mockito |
| Repository | `@DataJpaTest` + 실제 DB | Testcontainers (MySQL 권장) |
| 통합 | `@SpringBootTest` | Testcontainers |

각 엔드포인트 최소 케이스:
- 정상 (200/201)
- 검증 실패 (400)
- 인증 실패 (401)
- 인가 실패 (403)
- 리소스 없음 (404)
- 충돌 (409, 해당 시)

---

## 4. 작업 완료 전 자가 점검

- [ ] 모든 변경이 요청 범위 내인가? (Surgical Changes)
- [ ] Controller가 Entity를 노출하지 않는가?
- [ ] N+1 가능성 확인했는가? (`spring.jpa.show-sql=true`로 확인)
- [ ] 트랜잭션 경계가 적절한가?
- [ ] 검증/예외 처리가 일관된 응답으로 나가는가?
- [ ] 로그에 민감 정보 없는가?
- [ ] 보안 체크리스트 통과했는가?
- [ ] 테스트 작성하고 통과했는가?
- [ ] `./gradlew build` 또는 `./mvnw verify` 통과하는가?