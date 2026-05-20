# JPA / Hibernate / MySQL 규칙

## 1. 필수 설정 (application.yml)

```yaml
spring:
  jpa:
    open-in-view: false              # 반드시 false. 기본값 true는 OSIV로 인한 지연 로딩 함정 발생
    hibernate:
      ddl-auto: validate             # 프로덕션은 validate 또는 none. update/create 금지
    properties:
      hibernate:
        default_batch_fetch_size: 100   # N+1 완화
        jdbc:
          batch_size: 50               # 배치 insert/update
        order_inserts: true
        order_updates: true
        format_sql: true
    show-sql: false                  # 로컬 디버깅에만 true. 운영은 false
  datasource:
    hikari:
      maximum-pool-size: 10          # CPU 코어 수에 따라 조정
      connection-timeout: 3000
      max-lifetime: 1800000
```

`open-in-view: true` 는 절대 그대로 두지 마라. 진짜 큰 함정이다.

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
    private Long version;            // 낙관적 락 (필요시)

    // 정적 팩토리 메서드
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

    // 도메인 행위 메서드
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
- ❌ `@Setter` 클래스 레벨 사용 금지 → 도메인 행위 메서드로
- ❌ `@Data` 사용 금지 → equals/hashCode 위험
- ❌ `@AllArgsConstructor` 무분별 사용 → 정적 팩토리 메서드
- ❌ public 기본 생성자 → `@NoArgsConstructor(access = PROTECTED)` (JPA 요구사항만 충족)
- ✅ ID 생성 전략: MySQL은 `IDENTITY` (auto_increment). `AUTO` 절대 사용 금지 (Hibernate가 SEQUENCE 시도)
- ✅ Enum은 항상 `@Enumerated(STRING)`. `ORDINAL`은 절대 금지 (값 추가/순서 변경 시 데이터 깨짐)
- ✅ `length` 명시 (기본 255는 의미 없음). 인덱스 효율과 스키마 명세 목적
- ✅ Auditing 필드(createdAt, updatedAt)는 `@EntityListeners(AuditingEntityListener.class)` + `@CreatedDate`, `@LastModifiedDate` 사용 권장 (기존 프로젝트가 그 패턴이면)
- ✅ 동시 수정이 충돌 가능한 엔티티는 `@Version`으로 낙관적 락

## 3. 연관관계 매핑

### 절대 규칙: 모든 연관관계는 LAZY
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;

@OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
private List orders = new ArrayList<>();
```

- `@ManyToOne`의 기본값은 EAGER → **반드시 LAZY로 명시**
- `@OneToOne`의 기본값은 EAGER → **반드시 LAZY로 명시**
- EAGER는 N+1과 불필요한 조회를 일으킨다. 필요한 곳에서 fetch join으로 가져온다.

### 양방향은 정말 필요할 때만
- 가능하면 단방향. 양방향은 복잡도 + 직렬화 무한루프 위험.
- 양방향이 필요하면 연관관계 편의 메서드 작성.

```java
public class Order {
    @ManyToOne(fetch = FetchType.LAZY)
    private User user;

    public void assignTo(User user) {
        this.user = user;
        user.getOrders().add(this);   // 양방향 동기화
    }
}
```

### Cascade 주의
- `CascadeType.ALL` 무분별 사용 금지.
- 진짜 라이프사이클이 일치하는 경우(예: Order ↔ OrderItem)에만.
- 다른 Aggregate Root와는 cascade 사용 금지.

## 4. Repository 작성

```java
public interface UserRepository extends JpaRepository {

    // 메서드 명명 쿼리 (간단한 경우)
    Optional findByEmail(String email);

    boolean existsByEmail(String email);

    // 인덱스가 적용된 컬럼으로만 단순 조회

    // 복잡한 쿼리는 @Query
    @Query("""
        select u from User u
        where u.status = :status
          and u.createdAt >= :since
        """)
    Page findActiveSince(
            @Param("status") UserStatus status,
            @Param("since") LocalDateTime since,
            Pageable pageable);

    // N+1 방지: fetch join
    @Query("""
        select u from User u
        left join fetch u.orders
        where u.id = :id
        """)
    Optional findByIdWithOrders(@Param("id") Long id);

    // 또는 EntityGraph
    @EntityGraph(attributePaths = {"orders"})
    Optional findWithOrdersById(Long id);

    // 벌크 업데이트는 @Modifying + clearAutomatically
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.status = :status where u.id in :ids")
    int updateStatusByIds(@Param("status") UserStatus status, @Param("ids") List ids);
}
```

### Repository 규칙
- 메서드명 쿼리는 1-2 조건일 때만. 그 이상은 `@Query`.
- 동적 쿼리는 QueryDSL 또는 Specification (프로젝트가 어느 쪽을 쓰는지 확인).
- 네이티브 쿼리는 최후의 수단. JPQL로 안 될 때만.
- 페이징이 필요한 곳에서 `Pageable` 인자 추가.
- `@Modifying` 쿼리는 반드시 `clearAutomatically = true` (영속성 컨텍스트 동기화).

## 5. N+1 문제 — 가장 흔한 함정

### 증상
```text
// Service
List<User> users = userRepository.findAll();  // 1 쿼리
for (User user : users) {
    user.getOrders().size();  // 각 user마다 추가 쿼리 → N개
}
// 총 N+1 쿼리 발생
```

### 해결 방법 우선순위

**1. Fetch Join (JPQL)** — 가장 단순하고 명시적
```java
@Query("select u from User u left join fetch u.orders where u.status = :status")
List findActiveWithOrders(@Param("status") UserStatus status);
```

⚠️ Fetch Join + Pageable 함께 사용 시 메모리에서 페이징 발생 (경고 로그 출력). 페이징 필요 시 EntityGraph 또는 별도 쿼리 분리.

**2. EntityGraph** — 페이징과 호환
```java
@EntityGraph(attributePaths = {"orders"})
Page findByStatus(UserStatus status, Pageable pageable);
```

**3. default_batch_fetch_size** — 전역 설정으로 IN 쿼리 변환
```yaml
spring.jpa.properties.hibernate.default_batch_fetch_size: 100
```
→ 1 + N → 1 + (N/100)

**4. DTO Projection** — 연관 엔티티 자체가 필요 없을 때
```java
@Query("""
    select new com.example.user.dto.UserSummary(u.id, u.name, count(o))
    from User u left join u.orders o
    group by u.id
    """)
List findUserSummaries();
```

### 확인 방법
개발 환경에서 다음 설정으로 SQL 로그 확인:
```yaml
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```

리스트 조회 후 N개 이상의 SELECT가 나가면 N+1이다.

## 6. EntityManager / 영속성 컨텍스트 함정

### 변경 감지 (Dirty Checking)
영속 상태 엔티티의 필드 변경은 `save()` 호출 없이도 트랜잭션 종료 시 자동 반영.
```java
@Transactional
public void changeName(Long userId, String newName) {
    User user = userRepository.findById(userId).orElseThrow();
    user.changeName(newName);
    // save() 호출 불필요. 트랜잭션 종료 시 UPDATE 발생
}
```

### 준영속(Detached) 상태 주의
- 트랜잭션 외부의 엔티티는 detached.
- detached 엔티티의 변경은 자동 반영되지 않음.
- `merge()`는 새 영속 엔티티를 반환. 원본은 여전히 detached.

### 벌크 연산 후 영속성 컨텍스트 비우기
`@Modifying` 쿼리는 DB에 직접 반영되지만 영속성 컨텍스트는 옛 데이터 유지 → `clearAutomatically = true` 필수.

## 7. 인덱스 전략 (MySQL)

### 필수 인덱스
- 모든 외래키 컬럼 (MySQL은 자동 생성 안 됨)
- 자주 조회되는 WHERE 조건 컬럼
- 자주 정렬되는 ORDER BY 컬럼
- UNIQUE 제약은 자동으로 인덱스 생성

### 복합 인덱스
- 자주 함께 조회되는 컬럼은 복합 인덱스로.
- 순서 중요: 선택도 높은(카디널리티 큰) 컬럼이 앞으로.
- 예: `(status, created_at)` vs `(created_at, status)` — 사용 패턴에 따라.

### 인덱스 안 타는 패턴
- `WHERE LOWER(email) = ?` → 함수 적용 시 인덱스 안 탐. 함수형 인덱스 또는 정규화 필요.
- `WHERE column LIKE '%abc%'` → 앞 와일드카드는 인덱스 안 탐.
- `WHERE column != ?` → 효율 떨어짐.
- 형변환 (`WHERE int_col = '123'`) → 인덱스 안 탐 가능.

## 8. MySQL 특화 주의사항

### Charset / Collation
- 새 테이블은 항상 `utf8mb4` + `utf8mb4_unicode_ci` (이모지 지원).
- `utf8`은 MySQL에서 진짜 utf-8이 아님 (3바이트 제한).

### Timezone
- DB 컬럼은 `DATETIME` 또는 `TIMESTAMP`.
- `TIMESTAMP`는 자동 timezone 변환 (UTC 저장) — 의도 명확히.
- 애플리케이션과 DB의 timezone을 일치시킨다.
- application.yml:
```yaml
  spring.datasource.url: jdbc:mysql://.../db?serverTimezone=UTC&characterEncoding=utf8mb4
```

### Auto-increment 주의
- BIGINT 사용 권장 (INT는 21억 한계).
- 분산 환경에선 UUID 또는 별도 ID 생성기 고려.

## 9. 자가 점검
- [ ] open-in-view: false 설정되었는가?
- [ ] ddl-auto가 validate 또는 none인가?
- [ ] 모든 ManyToOne/OneToOne이 LAZY인가?
- [ ] N+1 위험을 확인하고 해결했는가? (SQL 로그로 검증)
- [ ] Entity에 @Setter/@Data 없는가?
- [ ] Enum이 @Enumerated(STRING)인가?
- [ ] 외래키 컬럼에 인덱스가 있는가?
- [ ] 자주 조회되는 컬럼에 인덱스가 있는가?
- [ ] 벌크 쿼리에 clearAutomatically = true 있는가?