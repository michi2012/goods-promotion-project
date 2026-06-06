package com.example.user_service.service;

import com.example.user_service.dto.request.UserCreateRequest;
import com.example.user_service.dto.request.UserPasswordChangeRequest;
import com.example.user_service.dto.request.UserUpdateRequest;
import com.example.user_service.dto.response.InternalUserResponse;
import com.example.user_service.dto.response.UserCreateResponse;
import com.example.user_service.dto.response.UserProfileResponse;
import com.example.user_service.dto.response.UserUpdateResponse;
import com.example.user_service.entity.User;
import com.example.user_service.exception.DuplicateEmailException;
import com.example.user_service.exception.DuplicatePhoneNumberException;
import com.example.user_service.exception.InvalidPasswordException;
import com.example.user_service.exception.UserNotFoundException;
import com.example.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;

    @Transactional
    public UserCreateResponse createUser(UserCreateRequest request) {
        return register(request, "ROLE_USER");
    }

    @Transactional
    public UserCreateResponse createAdmin(UserCreateRequest request) {
        return register(request, "ROLE_ADMIN");
    }

    @Transactional
    public UserCreateResponse createInternalUser(UserCreateRequest request) {
        return register(request, "ROLE_DRIVER");
    }

    @Transactional
    public void deleteUser(Long id, String authenticatedUserId) {
        User user = getUserOrThrow(id);

        validateOwner(user, authenticatedUserId);

        userRepository.delete(user);
        log.info("사용자 삭제 완료. ID: {}", id);
    }

    @Transactional
    public UserUpdateResponse updateUser(Long id, UserUpdateRequest request, String authenticatedUserId) {
        User user = getUserOrThrow(id);

        validateOwner(user, authenticatedUserId);

        user.update(request.username(), request.phoneNumber());

        return new UserUpdateResponse(user.getId(), user.getUserId(), user.getUsername(), user.getEmail(), user.getPhoneNumber());
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(Long id, String authenticatedUserId) {
        User user = getUserOrThrow(id);

        validateOwner(user, authenticatedUserId);

        return new UserProfileResponse(user.getId(), user.getUserId(), user.getEmail(), user.getUsername(), user.getPhoneNumber());
    }

    @Transactional
    public void changePassword(Long id, UserPasswordChangeRequest request, String authenticatedUserId) {
        User user = getUserOrThrow(id);

        validateOwner(user, authenticatedUserId);

        if (!bCryptPasswordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new InvalidPasswordException("현재 비밀번호가 일치하지 않습니다.");
        }

        user.setPassword(bCryptPasswordEncoder.encode(request.newPassword()));
        log.info("비밀번호 변경 완료. ID: {}", id);
    }

    @Transactional(readOnly = true)
    public InternalUserResponse getUserByUserId(String userId) {
        User user = userRepository.findByUserId(userId)
                                  .orElseThrow(() -> new UserNotFoundException("User not found with ID: " + userId));

        return InternalUserResponse.fromEntity(user);
    }

    private User getUserOrThrow(Long id) {
        return userRepository.findById(id)
                             .orElseThrow(() -> new UserNotFoundException("User not found with ID: " + id));
    }

    private void validateOwner(User targetUser, String authenticatedUserId) {
        if (!targetUser.getUserId().equals(authenticatedUserId)) {
            log.warn("권한 없는 접근 시도! Target PK: {}, Requester UUID: {}", targetUser.getId(), authenticatedUserId);
            throw new AccessDeniedException("본인의 정보만 수정/삭제할 수 있습니다.");
        }
    }

    private UserCreateResponse register(UserCreateRequest request, String role) {
        log.info("회원가입 요청: email={}, role={}", request.email(), role);

        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException("Email already exists: " + request.email());
        }
        if (userRepository.existsByPhoneNumber(request.phoneNumber())) {
            throw new DuplicatePhoneNumberException("Phone number already exists: " + request.phoneNumber());
        }

        User newUser = User.builder()
                           .email(request.email())
                           .username(request.username())
                           .role(role)
                           .password(bCryptPasswordEncoder.encode(request.password()))
                           .phoneNumber(request.phoneNumber())
                           .build();

        User savedUser = userRepository.save(newUser);
        log.info("사용자 생성 성공. ID: {}, Role: {}", savedUser.getId(), role);

        return new UserCreateResponse(savedUser.getId(), savedUser.getUserId(), savedUser.getEmail(), savedUser.getUsername());
    }
}