package com.example.user_service.repository;

import com.example.user_service.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
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

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("이메일로 사용자가 존재하는지 확인하면 true를 반환한다")
    void existsByEmail_whenUserExists_returnsTrue() {
        // Given
        User user = User.builder()
                        .email("test@example.com")
                        .password("password")
                        .username("Test User")
                        .role("USER")
                        .phoneNumber("01012345678")
                        .build();

        entityManager.persistAndFlush(user);

        // When
        boolean exists = userRepository.existsByEmail("test@example.com");

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("전화번호로 사용자가 존재하는지 확인하면 true를 반환한다")
    void existsByPhoneNumber_whenUserExists_returnsTrue() {
        // Given
        User user = User.builder()
                        .email("unique@example.com")
                        .password("password")
                        .username("Unique User")
                        .role("USER")
                        .phoneNumber("01099999999")
                        .build();

        entityManager.persistAndFlush(user);

        // When
        boolean exists = userRepository.existsByPhoneNumber("01099999999");

        // Then
        assertThat(exists).isTrue();
    }
}
