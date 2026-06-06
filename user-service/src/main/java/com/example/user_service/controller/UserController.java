package com.example.user_service.controller;

import com.example.user_service.dto.request.UserCreateRequest;
import com.example.user_service.dto.response.UserCreateResponse;
import com.example.user_service.dto.request.UserUpdateRequest;
import com.example.user_service.dto.response.UserUpdateResponse;
import com.example.user_service.dto.response.UserProfileResponse;
import com.example.user_service.dto.request.UserPasswordChangeRequest;
import com.example.user_service.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserCreateResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        UserCreateResponse response = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/admin-register")
    public ResponseEntity<UserCreateResponse> createAdmin(
            @Valid @RequestBody UserCreateRequest request,
            @RequestHeader(value = "X-Role", required = false) String role) {

        if (!"ROLE_ADMIN".equals(role)) {
            throw new AccessDeniedException("관리자만 관리자 계정을 생성할 수 있습니다.");
        }

        UserCreateResponse response = userService.createAdmin(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") String authenticatedUserId) {

        userService.deleteUser(id, authenticatedUserId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserUpdateResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserUpdateRequest userUpdateRequest,
            @RequestHeader("X-User-Id") String authenticatedUserId) {

        UserUpdateResponse response = userService.updateUser(id, userUpdateRequest, authenticatedUserId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserProfileResponse> getUserProfile(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") String authenticatedUserId) {

        UserProfileResponse response = userService.getUserProfile(id, authenticatedUserId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable Long id,
            @Valid @RequestBody UserPasswordChangeRequest request,
            @RequestHeader("X-User-Id") String authenticatedUserId) {

        userService.changePassword(id, request, authenticatedUserId);
        return ResponseEntity.ok().build();
    }
}