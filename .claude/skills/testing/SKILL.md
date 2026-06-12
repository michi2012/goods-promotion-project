---
name: testing
description: 테스트 코드 작성 시에만 활성화. @WebMvcTest, @DataJpaTest, @SpringBootTest, Mockito, Testcontainers, MockMvc 사용. 사용자가 테스트, test, 검증, TDD, 단위 테스트, 통합 테스트 를 언급하거나 src/test/ 폴더 작업 시 발동. 프로덕션 코드 작성 시에는 발동하지 않음.
---

# Spring Boot 테스트 작성 규칙

## 1. 테스트 종류와 도구

| 레이어 | 테스트 종류 | 어노테이션 | 특징 |
|---|---|---|---|
| Controller | 슬라이스 | `@WebMvcTest` | Web만, Service는 Mock |
| Service | 단위 | 없음 (POJO) | 모든 의존성 Mock |
| Repository | 슬라이스 | `@DataJpaTest` | JPA만, 실제 DB 권장 |
| 통합 | 통합 | `@SpringBootTest` | 전체 컨텍스트 |

## 2. Repository 테스트 (Testcontainers + MySQL, Singleton Container 패턴)

H2는 MySQL과 방언이 달라 함정 발생. **Testcontainers로 실제 MySQL 사용**.

클래스마다 컨테이너를 새로 띄우면(`@Container` + `@Testcontainers`) 테스트 클래스 수만큼 기동 비용(수 초)이 누적된다. **Singleton Container 패턴**으로 모듈 내 모든 Repository 테스트가 컨테이너 1개를 공유한다 — 추상 베이스 클래스에 `static` 필드로 컨테이너를 선언하고 정적 초기화 블록에서 `.start()`만 호출한다(`@Container`/`@Testcontainers` 미사용). 컨테이너 정리는 Testcontainers의 Ryuk 리소스 리퍼가 JVM 종료 시 자동 처리한다.

```java
// {module}/src/test/java/.../support/AbstractRepositoryTest.java
public abstract class AbstractRepositoryTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
```

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("이메일로 사용자를 조회할 수 있다")
    void findByEmail() {
        // given
        User user = User.create("test@example.com", "hash", "테스터");
        userRepository.save(user);

        // when
        Optional<User> found = userRepository.findByEmail("test@example.com");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("테스터");
    }
}
```

> `@SpringBootTest` 기반 통합 테스트(Kafka/Redis 포함)는 이미 동일한 패턴을 적용한 `AbstractSpringBootTest`를 사용한다. `AbstractRepositoryTest`는 `@DataJpaTest`/`@JdbcTest` 슬라이스 테스트 전용 — MySQL 컨테이너만 포함한다.

## 3. Service 단위 테스트

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("이메일 중복 시 예외가 발생한다")
    void createWithDuplicateEmail() {
        // given
        UserCreateRequest req = new UserCreateRequest("dup@example.com", "Pass1234", "테스터");
        given(userRepository.existsByEmail("dup@example.com")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> userService.create(req))
                .isInstanceOf(DuplicateEmailException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("정상적으로 사용자가 생성된다")
    void createSuccess() {
        // given
        UserCreateRequest req = new UserCreateRequest("new@example.com", "Pass1234", "신규");
        given(userRepository.existsByEmail("new@example.com")).willReturn(false);
        given(passwordEncoder.encode("Pass1234")).willReturn("hashed");
        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        UserResponse result = userService.create(req);

        // then
        assertThat(result.email()).isEqualTo("new@example.com");
        verify(userRepository).save(any(User.class));
    }
}
```

## 4. Controller 테스트

```java
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("정상 회원가입 요청 시 201을 반환한다")
    void createSuccess() throws Exception {
        UserCreateRequest req = new UserCreateRequest("test@example.com", "Pass1234", "테스터");
        UserResponse resp = new UserResponse(1L, "test@example.com", "테스터", LocalDateTime.now());
        given(userService.create(any())).willReturn(resp);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("test@example.com"));
    }

    @Test
    @DisplayName("이메일 형식이 잘못된 경우 400을 반환한다")
    void createWithInvalidEmail() throws Exception {
        UserCreateRequest req = new UserCreateRequest("invalid-email", "Pass1234", "테스터");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }
}
```

## 5. 테스트 작성 원칙

### Given-When-Then
```java
// given: 사전 상태
// when: 동작 실행
// then: 검증
```

### 한 테스트 = 한 시나리오
분기마다 별도 테스트.

### 한글 @DisplayName 권장
```text
@DisplayName("잔액이 부족하면 송금이 거절된다")
```

### AssertJ 사용
```text
assertThat(result).isEqualTo(expected);
assertThatThrownBy(() -> service.doSomething())
        .isInstanceOf(BusinessException.class);
```

### 결정성 (Deterministic)
- 시간: `Clock` 주입
- 난수: 시드 고정
- 외부 호출: Mock

### 각 엔드포인트 최소 테스트 케이스
- 정상 (200/201)
- 검증 실패 (400)
- 인증 실패 (401)
- 리소스 없음 (404)

## 6. 커버리지
- 핵심 비즈니스 로직: 거의 100%
- Controller: 주요 케이스
- Repository: 커스텀 쿼리 100%
- 단순 게터/세터: 안 해도 됨

## 7. 자가 점검
- [ ] 새 로직에 대한 테스트를 작성했는가?
- [ ] 실패 케이스도 테스트했는가?
- [ ] Testcontainers로 실제 MySQL을 쓰는가?
- [ ] 테스트가 결정적인가?
- [ ] 한 테스트가 한 시나리오만 검증하는가?
- [ ] `./gradlew test` 통과하는가?