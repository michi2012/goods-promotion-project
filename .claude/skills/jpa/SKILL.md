---
name: jpa
description: JPA 엔티티 생성/수정, 리포지토리 쿼리 작성, N+1 해결, 연관관계 매핑, 트랜잭션 처리, 벌크 연산, 락 전략 작업 시에만 활성화. 사용자가 엔티티, 리포지토리, JPA, 쿼리, N+1, fetch join, 트랜잭션, 락, 마이그레이션 을 언급하거나 entity/, domain/, repository/ 폴더 작업 시 발동. 단순 서비스 로직이나 컨트롤러 작업에서는 발동하지 않음.
---

# JPA / Hibernate / MySQL + 트랜잭션 규칙

## 0. 작업 시작 전

새 코드 작성 전 반드시 다음을 먼저 읽는다:
- 유사한 기존 Entity 1개
- 유사한 기존 Repository 1개
- `application.yml` 설정 (open-in-view, datasource, ddl-auto)

기존 패턴을 **그대로 따른다**. 새 패턴을 도입하지 마라.

---

# Part 1: JPA / Hibernate / MySQL

## 1. 필수 설정 (application.yml)

```yaml
spring:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_batch_fetch_size: 100
        jdbc:
          batch_size: 50
        order_inserts: true
        order_updates: true
        format_sql: true
    show-sql: false
  datasource:
    hikari:
      maximum-pool-size: 10
      connection-timeout: 3000
      max-lifetime: 1800000
```

`open-in-view: true` 는 절대 그대로 두지 마라.

## 2. Entity 작성 표준

```java
@Entity
@Table(name = "users",
       indexes = {
           @Index(name = "idx_users_email", columnList = "email", unique = true),
           @Index(name = "idx_users_created_at", columnList = "created_at")
       })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public static User create(String email, String passwordHash, String name) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.name = name;
        user.status = UserStatus.ACTIVE;
        user.createdAt = LocalDateTime.now();
        user.updatedAt = LocalDateTime.now();
        return user;
    }

    public void changeName(String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("이름은 비어있을 수 없습니다");
        }
        this.name = newName;
        this.updatedAt = LocalDateTime.now();
    }

    public void deactivate() {
        this.status = UserStatus.INACTIVE;
        this.updatedAt = LocalDateTime.now();
    }
}
```

### Entity 규칙
- ❌ `@Setter` 클래스 레벨 금지 → 도메인 행위 메서드로
- ❌ `@Data` 금지 → equals/hashCode 위험
- ❌ `@AllArgsConstructor` 무분별 사용 → 정적 팩토리 메서드
- ❌ public 기본 생성자 → `@NoArgsConstructor(access = PROTECTED)`
- ✅ MySQL ID 생성: `IDENTITY` (`AUTO` 절대 금지)
- ✅ Enum: `@Enumerated(STRING)` (`ORDINAL` 절대 금지)
- ✅ `length` 명시
- ✅ 동시 수정 가능 엔티티는 `@Version`

## 3. 연관관계 매핑

### 절대 규칙: 모든 연관관계는 LAZY
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;
```

- `@ManyToOne` 기본값 EAGER → **반드시 LAZY 명시**
- `@OneToOne` 기본값 EAGER → **반드시 LAZY 명시**
- 가능하면 단방향. 양방향은 정말 필요할 때만.
- `CascadeType.ALL` 무분별 사용 금지. 라이프사이클 일치 시에만.

## 4. Repository 작성

```java
public interface UserRepository extends JpaRepository {

    Optional findByEmail(String email);

    boolean existsByEmail(String email);

    @Query("""
        select u from User u
        where u.status = :status
          and u.createdAt >= :since
        """)
    Page findActiveSince(
            @Param("status") UserStatus status,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    @Query("""
        select u from User u
        left join fetch u.orders
        where u.id = :id
        """)
    Optional findByIdWithOrders(@Param("id") Long id);

    @EntityGraph(attributePaths = {"orders"})
    Optional findWithOrdersById(Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.status = :status where u.id in :ids")
    int updateStatusByIds(@Param("status") UserStatus status, @Param("ids") List ids);
}
```

### Repository 규칙
- 메서드명 쿼리는 1-2 조건만. 그 이상은 `@Query`.
- 네이티브 쿼리는 최후의 수단.
- 페이징: `Pageable` 인자 추가.
- `@Modifying`은 반드시 `clearAutomatically = true`.

## 5. N+1 문제

### 해결 우선순위
1. **Fetch Join** — 가장 단순. 단, Pageable과 함께 사용 시 메모리 페이징 주의.
2. **EntityGraph** — 페이징과 호환.
3. **default_batch_fetch_size** — 전역 설정으로 IN 쿼리 변환.
4. **DTO Projection** — 연관 엔티티 자체가 필요 없을 때.

### 확인 방법
개발 환경에서 `spring.jpa.show-sql: true` + `org.hibernate.SQL: DEBUG` 설정 후 리스트 조회 시 N개 이상의 SELECT가 나가면 N+1이다.

## 6. 영속성 컨텍스트 함정

### 변경 감지 (Dirty Checking)
영속 상태 엔티티 필드 변경은 `save()` 없이도 트랜잭션 종료 시 자동 반영.

### 준영속(Detached) 주의
트랜잭션 외부 엔티티는 detached. 변경이 자동 반영되지 않음.

### 벌크 연산 후
`@Modifying` 쿼리는 DB에 직접 반영되지만 영속성 컨텍스트는 옛 데이터 유지 → `clearAutomatically = true` 필수.

## 7. 인덱스 전략 (MySQL)
- 모든 외래키 컬럼에 인덱스 (MySQL은 자동 생성 안 됨)
- WHERE 자주 쓰는 컬럼, ORDER BY 자주 쓰는 컬럼
- 복합 인덱스: 선택도 높은 컬럼이 앞으로
- 인덱스 안 타는 패턴: `LOWER()`, `LIKE '%...'`, 형변환

## 8. MySQL 특화
- charset: `utf8mb4` + `utf8mb4_unicode_ci`
- ID: BIGINT 권장
- timezone: application.yml에 `serverTimezone=UTC` 명시

---

# Part 2: 트랜잭션 처리

## 9. 기본 원칙

### 트랜잭션 경계는 Service 레이어만
- ❌ Controller에 `@Transactional` 금지
- ❌ Repository에 `@Transactional` 금지
- ✅ Service 메서드에 `@Transactional`

### 클래스 레벨 readOnly + 변경 메서드에만 명시적 @Transactional

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    public UserResponse getById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse create(UserCreateRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }
        User user = User.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name()
        );
        userRepository.save(user);
        return UserResponse.from(user);
    }
}
```

## 10. 트랜잭션 전파

| Propagation | 의미 | 사용 시점 |
|---|---|---|
| REQUIRED (기본) | 있으면 참여, 없으면 생성 | 대부분 |
| REQUIRES_NEW | 항상 새 트랜잭션 | 부모 롤백과 독립 (감사 로그 등) |
| MANDATORY | 기존 필수 | 내부 헬퍼 |

## 11. 롤백 규칙
- RuntimeException → 자동 롤백
- Checked Exception → 롤백 안 됨 (기본)
- 비즈니스 예외는 RuntimeException 상속 권장

## 12. 자가 호출 함정

```java
public void outer() {
    this.inner();   // ❌ @Transactional 동작 안 함!
}
```

Spring AOP는 프록시 기반. `this.method()`는 프록시를 거치지 않음.
→ 메서드를 별도 빈으로 분리.

## 13. 외부 호출과 트랜잭션

❌ 안티패턴:
```java
@Transactional
public void process(Long id) {
    User user = userRepository.findById(id).orElseThrow();
    externalApi.call(user);     // 트랜잭션 안에서 네트워크 호출
}
```

✅ 해결: 외부 호출을 트랜잭션 외부로 분리.

## 14. 락 전략
- 낙관적 락: `@Version` (충돌 드문 경우)
- 비관적 락: `@Lock(PESSIMISTIC_WRITE)` (재고 차감, 잔액 변경)

## 15. 자가 점검
- [ ] open-in-view: false 설정되었는가?
- [ ] ddl-auto가 validate 또는 none인가?
- [ ] 모든 ManyToOne/OneToOne이 LAZY인가?
- [ ] N+1 위험 확인했는가?
- [ ] Entity에 @Setter/@Data 없는가?
- [ ] Enum이 @Enumerated(STRING)인가?
- [ ] Service에만 @Transactional이 있는가?
- [ ] 클래스 레벨 readOnly = true 적용했는가?
- [ ] 자가 호출 함정 없는가?
- [ ] 외부 API 호출이 트랜잭션 내부에 없는가?
- [ ] 벌크 쿼리에 clearAutomatically = true 있는가?