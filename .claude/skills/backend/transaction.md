# Spring 트랜잭션 처리 규칙

## 1. 기본 원칙

### 1-1. 트랜잭션 경계는 Service 레이어
- ❌ Controller에 `@Transactional` 금지
- ❌ Repository에 `@Transactional` 금지 (Spring Data JPA가 자체적으로 처리)
- ✅ Service 메서드에 `@Transactional`

### 1-2. 클래스 레벨 vs 메서드 레벨
권장: **클래스 레벨 readOnly = true, 변경 메서드에만 메서드 레벨 @Transactional**

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    // readOnly = true 자동 적용
    public UserResponse getById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        return UserResponse.from(user);
    }

    public Page<UserResponse> list(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    // 변경 메서드는 명시적으로 readOnly 해제
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

### 1-3. readOnly = true의 이점
- Hibernate가 영속성 컨텍스트의 스냅샷을 만들지 않음 (변경 감지 비활성화) → 성능 향상
- 일부 DB에서 읽기 전용 트랜잭션 최적화
- 실수로 변경 작업이 들어가도 catch (예외 또는 무시)

## 2. 트랜잭션 전파 (Propagation)

| Propagation | 의미 | 사용 시점 |
|---|---|---|
| REQUIRED (기본) | 트랜잭션 있으면 참여, 없으면 새로 생성 | 대부분의 경우 |
| REQUIRES_NEW | 항상 새 트랜잭션 (기존은 일시 정지) | 부모 롤백과 독립되어야 하는 작업 (감사 로그 등) |
| MANDATORY | 반드시 기존 트랜잭션 필요. 없으면 예외 | 내부 헬퍼 메서드 |
| SUPPORTS | 있으면 참여, 없어도 진행 | 조회용 (드물게) |
| NEVER | 트랜잭션 없을 때만 실행 | 거의 안 씀 |
| NESTED | 중첩 트랜잭션 (SAVEPOINT) | MySQL InnoDB에서 동작 |

### 자주 쓰는 패턴

**부모 작업이 실패해도 감사 로그는 남겨야 한다:**
```java
@Service
public class OrderService {
    @Transactional
    public Order place(OrderRequest req) {
        Order order = new Order();
        try {
            paymentService.charge();
        } catch (PaymentException e) {
            auditService.logFailure();  // REQUIRES_NEW로 분리되어 있음
            throw e;
        }
        return order;
    }
}

@Service
public class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure() {}
}
```

## 3. 롤백 규칙

### 기본 동작
- **RuntimeException** 및 **Error** → 자동 롤백
- **Checked Exception** → 롤백 **안 됨** (기본)

### Checked Exception도 롤백하려면
```java
@Transactional(rollbackFor = IOException.class)
public void process() throws IOException {}
```

### 권장 패턴
비즈니스 예외는 모두 `RuntimeException`을 상속한 커스텀 예외로 만든다 → 명시적 `rollbackFor` 불필요.

```java
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(Long id) {
        super("사용자를 찾을 수 없습니다: " + id);
    }
}
```

## 4. 자가 호출 함정 (Self-invocation)

```java
@Service
public class UserService {
    public void outerMethod() {
        this.innerMethod();   // ❌ @Transactional 동작 안 함!
    }

    @Transactional
    public void innerMethod() {
        // ...
    }
}
```

### 이유
Spring AOP는 프록시 기반. `this.innerMethod()`는 프록시를 거치지 않음.

### 해결
**Option 1**: 메서드를 별도 빈으로 분리 (권장).
```java
@Service
@RequiredArgsConstructor
public class UserFacade {
    private final UserService userService;

    public void outer() {
        userService.inner();   // 프록시 통해 호출 → @Transactional 적용
    }
}
```

**Option 2**: ApplicationContext에서 자기 자신 빈을 다시 가져오기 (안티패턴, 비추천).

**Option 3**: TransactionTemplate 직접 사용 (특수한 경우).

## 5. 외부 호출과 트랜잭션

### ❌ 안티패턴: 긴 트랜잭션
```java
@Transactional
public void process(Long id) {
    User user = userRepository.findById(id).orElseThrow();
    externalApi.call(user);     // 네트워크 호출 — 수 초 걸릴 수 있음
    user.markProcessed();
}
```

문제:
- 트랜잭션이 외부 호출 시간만큼 길어짐
- DB 커넥션 점유 → 커넥션 풀 고갈
- 외부 시스템 장애가 DB 트랜잭션에 영향

### ✅ 해결: 외부 호출을 트랜잭션 외부로
```java
public void process(Long id) {
    UserData userData = loadUserData(id);  // 짧은 트랜잭션
    ExternalResult result = externalApi.call(userData);  // 트랜잭션 외부
    markProcessed(id, result);  // 짧은 트랜잭션
}

@Transactional(readOnly = true)
protected UserData loadUserData(Long id) {
    return userRepository.findById(id)
            .map(UserData::from)
            .orElseThrow();
}

@Transactional
protected void markProcessed(Long id, ExternalResult result) {
    User user = userRepository.findById(id).orElseThrow();
    user.applyResult(result);
}
```

(주의: `protected` + 같은 클래스 호출은 self-invocation. 실제로는 별도 빈으로 분리)

## 6. 락 전략

### 낙관적 락 (Optimistic Locking)
충돌이 드문 경우. `@Version` 필드 추가.
```java
@Entity
public class User {
    @Version
    private Long version;
}
```
충돌 시 `OptimisticLockingFailureException` 발생 → 재시도 또는 사용자에게 알림.

### 비관적 락 (Pessimistic Locking)
충돌이 잦거나 데이터 무결성이 중요한 경우.
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from Stock s where s.id = :id")
Stock findByIdForUpdate(@Param("id") Long id);
```

MySQL에서 `SELECT ... FOR UPDATE` 발생. 트랜잭션 동안 행 락.

### 권장
- 일반적: 낙관적 락
- 재고 차감, 잔액 변경 등: 비관적 락 (또는 별도 락 메커니즘)

## 7. 자가 점검
- [ ] Service에만 @Transactional이 있는가?
- [ ] 클래스 레벨 readOnly = true, 변경 메서드에 명시적 @Transactional 적용했는가?
- [ ] 비즈니스 예외가 RuntimeException 상속인가?
- [ ] 자가 호출(self-invocation) 함정에 빠지지 않았는가?
- [ ] 외부 API 호출이 트랜잭션 내부에 들어있지 않은가?
- [ ] 동시성 충돌이 가능한 곳에 적절한 락이 적용되었는가?