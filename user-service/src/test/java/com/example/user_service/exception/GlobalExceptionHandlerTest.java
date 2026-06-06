package com.example.user_service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler globalExceptionHandler = new GlobalExceptionHandler();

    @Test
    @DisplayName("DuplicateEmailException 발생 시 409 Conflict와 메시지를 반환한다")
    void handleDuplicateEmailException_returns409ConflictWithMessage() {
        // Given
        DuplicateEmailException ex = new DuplicateEmailException("Test email already exists");

        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleDuplicateEmailException(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Test email already exists");
    }

    @Test
    @DisplayName("DuplicatePhoneNumberException 발생 시 409 Conflict와 메시지를 반환한다")
    void handleDuplicatePhoneNumberException_returns409ConflictWithMessage() {
        // Given
        DuplicatePhoneNumberException ex = new DuplicatePhoneNumberException("Test phone number already exists");

        // When
        ResponseEntity<ErrorResponse> response = globalExceptionHandler.handleDuplicatePhoneNumberException(ex);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Test phone number already exists");
    }
}