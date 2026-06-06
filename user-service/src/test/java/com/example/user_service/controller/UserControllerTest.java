package com.example.user_service.controller;

import com.example.user_service.dto.request.UserCreateRequest;
import com.example.user_service.dto.request.UserPasswordChangeRequest;
import com.example.user_service.dto.request.UserUpdateRequest;
import com.example.user_service.dto.response.UserCreateResponse;
import com.example.user_service.dto.response.UserProfileResponse;
import com.example.user_service.dto.response.UserUpdateResponse;
import com.example.user_service.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false) // 시큐리티 필터 끄기
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    private final String TEST_UUID = "user-uuid-123";

    @Test
    @DisplayName("유효한 사용자 정보로 사용자 생성 요청을 보내면 201 Created 응답을 받는다")
    void createUser_withValidUserInfo_returns201Created() throws Exception {
        // Given
        UserCreateRequest request = new UserCreateRequest(
                "test@example.com", "password123", "Test User", "01012345678"
        );
        UserCreateResponse response = new UserCreateResponse(1L, TEST_UUID, "test@example.com", "Test User");

        when(userService.createUser(any(UserCreateRequest.class))).thenReturn(response);

        // When & Then
        mockMvc.perform(post("/api/users")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    @DisplayName("유효한 ID와 헤더로 사용자 삭제 요청을 보내면 204 No Content 응답을 받는다")
    void deleteUser_withValidId_returns204NoContent() throws Exception {
        // Given
        Long userId = 1L;
        doNothing().when(userService).deleteUser(userId, TEST_UUID);

        // When & Then
        mockMvc.perform(delete("/api/users/{id}", userId)
                       .header("X-User-Id", TEST_UUID)) // ✅ 헤더 추가
               .andExpect(status().isNoContent());

        verify(userService, times(1)).deleteUser(userId, TEST_UUID);
    }

    @Test
    @DisplayName("유효한 정보와 헤더로 사용자 업데이트 요청을 보내면 200 OK 응답을 받는다")
    void updateUser_withValidUserInfo_returns200Ok() throws Exception {
        // Given
        Long userId = 1L;
        UserUpdateRequest request = new UserUpdateRequest("Updated Name", "010-9876-5432");

        when(userService.updateUser(eq(userId), any(UserUpdateRequest.class), eq(TEST_UUID)))
                .thenReturn(new UserUpdateResponse(userId, TEST_UUID, request.username(), "test@example.com", request.phoneNumber()));

        // When & Then
        mockMvc.perform(put("/api/users/{id}", userId)
                       .header("X-User-Id", TEST_UUID)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isOk());

        verify(userService, times(1)).updateUser(eq(userId), any(UserUpdateRequest.class), eq(TEST_UUID));
    }

    @Test
    @DisplayName("유효하지 않은 이름으로 사용자 업데이트 요청을 보내면 400 Bad Request 응답을 받는다")
    void updateUser_withInvalidName_returns400BadRequest() throws Exception {
        // Given
        Long userId = 1L;
        UserUpdateRequest request = new UserUpdateRequest("", "010-9876-5432");

        // When & Then
        mockMvc.perform(put("/api/users/{id}", userId)
                       .header("X-User-Id", TEST_UUID)
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isBadRequest());

        verify(userService, never()).updateUser(anyLong(), any(UserUpdateRequest.class), anyString());
    }

    @Test
    @DisplayName("유효한 ID와 헤더로 사용자 프로필 조회 요청을 보내면 200 OK 응답을 받는다")
    void getUserProfile_withValidId_returns200OkAndUserProfile() throws Exception {
        // Given
        Long userId = 1L;
        UserProfileResponse expectedResponse = new UserProfileResponse(userId, TEST_UUID, "test@example.com", "Test User", "010-1234-5678");

        when(userService.getUserProfile(userId, TEST_UUID)).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(get("/api/users/{id}", userId)
                       .header("X-User-Id", TEST_UUID)) // ✅ 헤더 추가
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.id").value(userId));

        verify(userService, times(1)).getUserProfile(userId, TEST_UUID);
    }

    @Test
    @DisplayName("유효한 비밀번호 변경 요청을 보내면 200 OK 응답을 받는다")
    void changePassword_withValidRequest_returns200Ok() throws Exception {
        // Given
        Long userId = 1L;
        UserPasswordChangeRequest request = new UserPasswordChangeRequest("oldPassword", "newPassword");

        doNothing().when(userService).changePassword(eq(userId), any(UserPasswordChangeRequest.class), eq(TEST_UUID));

        // When & Then
        mockMvc.perform(put("/api/users/{id}/password", userId)
                       .header("X-User-Id", TEST_UUID) // ✅ 헤더 추가
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isOk());

        verify(userService, times(1)).changePassword(eq(userId), any(UserPasswordChangeRequest.class), eq(TEST_UUID));
    }

}