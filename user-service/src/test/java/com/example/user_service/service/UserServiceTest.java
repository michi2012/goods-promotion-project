package com.example.user_service.service;

import com.example.user_service.dto.request.UserCreateRequest;
import com.example.user_service.dto.request.UserPasswordChangeRequest;
import com.example.user_service.dto.request.UserUpdateRequest;
import com.example.user_service.dto.response.InternalUserResponse;
import com.example.user_service.dto.response.UserCreateResponse;
import com.example.user_service.dto.response.UserUpdateResponse;
import com.example.user_service.dto.response.UserProfileResponse;
import com.example.user_service.entity.User;
import com.example.user_service.exception.DuplicateEmailException;
import com.example.user_service.exception.DuplicatePhoneNumberException;
import com.example.user_service.exception.InvalidPasswordException;
import com.example.user_service.exception.UserNotFoundException;
import com.example.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    @InjectMocks
    private UserService userService;

    private UserCreateRequest userCreateRequest;
    private User user;
    private final String testUserId = "test-uuid-123";
    private final Long testId = 1L;

    @BeforeEach
    void setUp() {
        userCreateRequest = new UserCreateRequest(
                "test@example.com",
                "password123",
                "Test User",
                "01012345678"
        );

        user = User.builder()
                   .email("test@example.com")
                   .password("encoded_password")
                   .username("Test User")
                   .role("ROLE_USER")
                   .phoneNumber("01012345678")
                   .build();

        ReflectionTestUtils.setField(user, "id", testId);
        ReflectionTestUtils.setField(user, "userId", testUserId);
    }

    @Test
    @DisplayName("일반 회원가입 시 ROLE_USER 권한으로 생성되어야 한다")
    void createUser_ShouldForceRoleUser() {
        // Given
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(bCryptPasswordEncoder.encode(anyString())).thenReturn("encoded_password");
        when(userRepository.save(any(User.class))).thenReturn(user);

        // When
        UserCreateResponse response = userService.createUser(userCreateRequest);

        // Then
        assertThat(response).isNotNull();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getRole()).isEqualTo("ROLE_USER");
        assertThat(savedUser.getEmail()).isEqualTo(userCreateRequest.email());
    }

    @Test
    @DisplayName("내부 호출로 기사 가입 시 지정한 Role(ROLE_DRIVER)로 생성되어야 한다")
    void createInternalUser_ShouldUseProvidedRole() {
        // Given
        String targetRole = "ROLE_DRIVER";

        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(bCryptPasswordEncoder.encode(anyString())).thenReturn("encoded_password");

        User driverUser = User.builder()
                              .role(targetRole)
                              .build();
        ReflectionTestUtils.setField(driverUser, "id", 2L);
        ReflectionTestUtils.setField(driverUser, "userId", "driver-uuid");

        when(userRepository.save(any(User.class))).thenReturn(driverUser);

        // When
        UserCreateResponse response = userService.createInternalUser(userCreateRequest);

        // Then
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getRole()).isEqualTo(targetRole);
    }

    @Test
    @DisplayName("이미 존재하는 이메일로 생성 시 DuplicateEmailException 발생")
    void createUser_DuplicateEmail() {
        // Given
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> userService.createUser(userCreateRequest))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("이미 존재하는 번호로 생성 시 DuplicatePhoneNumberException 발생")
    void createUser_DuplicatePhone() {
        // Given
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(true);

        // When & Then
        assertThatThrownBy(() -> userService.createUser(userCreateRequest))
                .isInstanceOf(DuplicatePhoneNumberException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("본인이 자신의 계정을 삭제하면 성공한다")
    void deleteUser_withValidOwner_shouldSucceed() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = testUserId;

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        doNothing().when(userRepository).delete(user);

        // When
        userService.deleteUser(userId, authenticatedUserId);

        // Then
        verify(userRepository, times(1)).delete(user);
    }

    @Test
    @DisplayName("타인이 계정 삭제를 시도하면 AccessDeniedException이 발생한다")
    void deleteUser_withInvalidOwner_shouldThrowAccessDeniedException() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = "other-user-uuid";

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When & Then
        assertThatThrownBy(() -> userService.deleteUser(userId, authenticatedUserId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("본인의 정보만 수정/삭제할 수 있습니다");

        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("존재하지 않는 ID로 사용자를 삭제하면 UserNotFoundException이 발생한다")
    void deleteUser_withNonExistentId_shouldThrowUserNotFoundException() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = testUserId;

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> userService.deleteUser(userId, authenticatedUserId))
                .isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("본인이 자신의 정보를 업데이트하면 성공한다")
    void updateUser_withValidOwner_shouldSucceed() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = testUserId;
        UserUpdateRequest request = new UserUpdateRequest("Updated Username", "010-9876-5432");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When
        UserUpdateResponse response = userService.updateUser(userId, request, authenticatedUserId);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.username()).isEqualTo(request.username());
        assertThat(response.phoneNumber()).isEqualTo(request.phoneNumber());
    }

    @Test
    @DisplayName("타인이 정보 수정을 시도하면 AccessDeniedException이 발생한다")
    void updateUser_withInvalidOwner_shouldThrowAccessDeniedException() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = "other-uuid";
        UserUpdateRequest request = new UserUpdateRequest("Updated", "010-0000-0000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When & Then
        assertThatThrownBy(() -> userService.updateUser(userId, request, authenticatedUserId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("본인이 자신의 프로필을 조회하면 성공한다")
    void getUserProfile_withValidOwner_shouldReturnUserProfileResponse() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = testUserId;

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When
        UserProfileResponse response = userService.getUserProfile(userId, authenticatedUserId);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.userId()).isEqualTo(testUserId);
    }

    @Test
    @DisplayName("본인이 비밀번호 변경을 시도하고 기존 비밀번호가 맞으면 성공한다")
    void changePassword_withValidOwnerAndCorrectPassword_shouldSucceed() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = testUserId;
        String oldPassword = "old_password";
        String newPassword = "new_password";
        UserPasswordChangeRequest request = new UserPasswordChangeRequest(oldPassword, newPassword);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(bCryptPasswordEncoder.matches(oldPassword, "encoded_password")).thenReturn(true);
        when(bCryptPasswordEncoder.encode(newPassword)).thenReturn("new_encoded_password");

        // When
        userService.changePassword(userId, request, authenticatedUserId);

        // Then
        assertThat(user.getPassword()).isEqualTo("new_encoded_password");
    }

    @Test
    @DisplayName("타인이 비밀번호 변경을 시도하면 AccessDeniedException이 발생한다")
    void changePassword_withInvalidOwner_shouldThrowAccessDeniedException() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = "hacker-uuid";
        UserPasswordChangeRequest request = new UserPasswordChangeRequest("pw", "pw");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // When & Then
        assertThatThrownBy(() -> userService.changePassword(userId, request, authenticatedUserId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("현재 비밀번호가 일치하지 않으면 InvalidPasswordException이 발생한다")
    void changePassword_withIncorrectOldPassword_shouldThrowException() {
        // Given
        Long userId = 1L;
        String authenticatedUserId = testUserId;
        String wrongPassword = "wrong_password";
        UserPasswordChangeRequest request = new UserPasswordChangeRequest(wrongPassword, "new");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(bCryptPasswordEncoder.matches(wrongPassword, "encoded_password")).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> userService.changePassword(userId, request, authenticatedUserId))
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessageContaining("비밀번호가 일치하지 않습니다");
    }

    @Test
    @DisplayName("유효한 userId로 조회 시 InternalUserResponse를 반환하며 성공한다")
    void getUserByUserId_withValidUserId_shouldReturnInternalUserResponse() {
        // Given
        when(userRepository.findByUserId(testUserId)).thenReturn(Optional.of(user));

        // When
        InternalUserResponse response = userService.getUserByUserId(testUserId);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(testId);
        assertThat(response.userId()).isEqualTo(testUserId);
        verify(userRepository, times(1)).findByUserId(testUserId);
    }
}