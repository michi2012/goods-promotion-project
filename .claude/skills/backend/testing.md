# Spring Boot 테스트 작성 규칙

## 1. 테스트 종류와 도구

| 레이어 | 테스트 종류 | 어노테이션 | 특징 |
|---|---|---|---|
| Controller | 슬라이스 테스트 | `@WebMvcTest` | Web 레이어만, Service는 Mock |
| Service | 단위 테스트 | 없음 (POJO) | 모든 의존성 Mock |
| Repository | 슬라이스 테스트 | `@DataJpaTest` | JPA만, 실제 DB 사용 권장 |
| 통합 | 통합 테스트 | `@SpringBootTest` | 전체 컨텍스트 |

## 2. Repository 테스트 (Testcontainers + MySQL 권장)

H2는 MySQL과 SQL 방언이 달라 함정 발생. **Testcontainers로 실제 MySQL 사용**.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

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

    @Test
    @DisplayName("이메일 중복 확인이 동작한다")
    void existsByEmail() {
        userRepository.save(User.create("a@example.com", "hash", "A"));

        assertThat(userRepository.existsByEmail("a@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("b@example.com")).isFalse();
    }
}
```

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
        assertThat(result.name()).isEqualTo("신규");
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
        // given
        UserCreateRequest req = new UserCreateRequest("test@example.com", "Pass1234", "테스터");
        UserResponse resp = new UserResponse(1L, "test@example.com", "테스터", LocalDateTime.now());
        given(userService.create(any())).willReturn(resp);

        // when & then
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

    @Test
    @DisplayName("중복 이메일 시 409를 반환한다")
    void createWithDuplicateEmail() throws Exception {
        UserCreateRequest req = new UserCreateRequest("dup@example.com", "Pass1234", "테스터");
        given(userService.create(any())).willThrow(new DuplicateEmailException("dup@example.com"));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_EMAIL"));
    }
}
```

## 5. 테스트 작성 원칙

### 5-1. 명명: 한글 `@DisplayName` 권장
```java
@Test
@DisplayName("잔액이 부족하면 송금이 거절된다")
void transferWithInsufficientBalance() {}
```

### 5-2. AAA 패턴 (Given-When-Then)
```java
@Test
void test() {
    // given: 사전 상태
    
    // when: 동작 실행
    
    // then: 검증
}
```

### 5-3. 한 테스트 = 한 시나리오
- 한 `@Test`에서 여러 시나리오 검증 금지.
- 분기마다 별도 테스트로.

### 5-4. AssertJ 사용
```text
assertThat(result).isEqualTo(expected);
assertThat(list).hasSize(3).contains("a", "b");
assertThatThrownBy(() -> service.doSomething())
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("잔액");
```

### 5-5. 결정성 (Deterministic)
- 시간: `Clock` 빈을 주입받아 테스트에서 고정.
- 난수: 시드 고정 또는 추상화.
- 외부 호출: Mock.

### 5-6. 테스트는 독립적
- 테스트 간 순서에 의존하면 안 됨.
- `@DirtiesContext` 남용 금지 (느려짐).
- `@Transactional` 테스트는 자동 롤백 → 다음 테스트 깨끗.

## 6. 커버리지 목표

엄격한 숫자는 무의미. 단:
- **핵심 비즈니스 로직**: 거의 100%
- **Controller**: 주요 케이스 (정상, 검증실패, 권한실패)
- **Repository**: 커스텀 쿼리는 100%
- **단순 게터/세터**: 굳이 안 해도 됨

## 7. 자가 점검
- [ ] 새 로직에 대한 테스트를 작성했는가?
- [ ] 정상 케이스뿐 아니라 실패 케이스도 테스트했는가?
- [ ] Repository 테스트가 H2가 아닌 실제 MySQL(Testcontainers)을 쓰는가?
- [ ] 테스트가 결정적인가? (시간/난수/외부 의존 격리)
- [ ] 한 테스트가 한 시나리오만 검증하는가?
- [ ] @DisplayName으로 의도가 명확한가?
- [ ] `./gradlew test` 또는 `./mvnw test` 통과하는가?