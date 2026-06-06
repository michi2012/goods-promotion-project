package com.example.user_service.service;

import com.example.user_service.dto.request.PaymentMethodRegisterRequest;
import com.example.user_service.dto.response.PaymentMethodRegisterResponse;
import com.example.user_service.dto.response.UserInfoForPaymentResponse;
import com.example.user_service.entity.PaymentMethod;
import com.example.user_service.entity.User;
import com.example.user_service.exception.UserNotFoundException;
import com.example.user_service.repository.PaymentMethodRepository;
import com.example.user_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.List;
import java.util.Arrays;
import com.example.user_service.dto.response.PaymentMethodResponse;
import com.example.user_service.exception.PaymentMethodNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentMethodServiceTest {

    @Mock
    private PaymentMethodRepository paymentMethodRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PaymentMethodService paymentMethodService;

    private User testUser;
    private final Long testId = 1L;
    private final String testUserUuid = "test-user-uuid-1L";

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                       .email("test@example.com")
                       .password("password123")
                       .username("Test User")
                       .role("USER")
                       .phoneNumber("01012345678")
                       .build();

        ReflectionTestUtils.setField(testUser, "id", testId);
        ReflectionTestUtils.setField(testUser, "userId", testUserUuid);
    }

    @Test
    @DisplayName("새로운 결제 수단 등록 시, 첫 결제 수단이면 기본 결제 수단으로 설정된다")
    void registerPaymentMethod_firstPaymentMethod_setsAsDefault() {
        // Given
        PaymentMethodRegisterRequest request = PaymentMethodRegisterRequest.builder()
                                                                           .cardNumber("4111-1111-1111-1111")
                                                                           .expiryDate("12/25")
                                                                           .cvc("123")
                                                                           .build();

        when(userRepository.findById(testId)).thenReturn(Optional.of(testUser));
        when(paymentMethodRepository.existsByUserId(testId)).thenReturn(false);
        when(paymentMethodRepository.save(any(PaymentMethod.class))).thenAnswer(invocation -> {
            PaymentMethod pm = invocation.getArgument(0);
            ReflectionTestUtils.setField(pm, "id", 1L);
            return pm;
        });

        // When
        PaymentMethodRegisterResponse response = paymentMethodService.registerPaymentMethod(testId, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.isDefault()).isTrue();
        assertThat(response.cardIssuer()).isEqualTo("Visa");
        assertThat(response.cardNumberMasked()).isEqualTo("4111-XXXX-XXXX-1111");
        verify(paymentMethodRepository, times(1)).save(any(PaymentMethod.class));
    }

    @Test
    @DisplayName("기존 결제 수단이 있는 경우, 새로운 결제 수단은 기본 결제 수단으로 설정되지 않는다")
    void registerPaymentMethod_existingPaymentMethods_doesNotSetAsDefault() {
        // Given
        PaymentMethodRegisterRequest request = PaymentMethodRegisterRequest.builder()
                                                                           .cardNumber("5111-1111-1111-1111")
                                                                           .expiryDate("12/25")
                                                                           .cvc("123")
                                                                           .build();

        when(userRepository.findById(testId)).thenReturn(Optional.of(testUser));
        when(paymentMethodRepository.existsByUserId(testId)).thenReturn(true);
        when(paymentMethodRepository.save(any(PaymentMethod.class))).thenAnswer(invocation -> {
            PaymentMethod pm = invocation.getArgument(0);
            ReflectionTestUtils.setField(pm, "id", 2L);
            return pm;
        });

        // When
        PaymentMethodRegisterResponse response = paymentMethodService.registerPaymentMethod(testId, request);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.isDefault()).isFalse();
        assertThat(response.cardIssuer()).isEqualTo("MasterCard");
        assertThat(response.cardNumberMasked()).isEqualTo("5111-XXXX-XXXX-1111");
        verify(paymentMethodRepository, times(1)).save(any(PaymentMethod.class));
    }

    @Test
    @DisplayName("존재하지 않는 사용자 ID로 결제 수단 등록 시 UserNotFoundException이 발생한다")
    void registerPaymentMethod_userNotFound_throwsUserNotFoundException() {
        // Given
        Long nonExistentId = 999L;
        PaymentMethodRegisterRequest request = PaymentMethodRegisterRequest.builder()
                                                                           .cardNumber("4111-1111-1111-1111")
                                                                           .expiryDate("12/25")
                                                                           .cvc("123")
                                                                           .build();

        when(userRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> paymentMethodService.registerPaymentMethod(nonExistentId, request))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("User not found with ID: 999");

        verify(paymentMethodRepository, never()).save(any(PaymentMethod.class));
    }

    @Test
    @DisplayName("유효한 사용자 ID로 결제 수단 목록 조회 시 목록을 반환한다")
    void getPaymentMethods_withValidUserId_returnsListOfPaymentMethods() {
        // Given
        Long userId = testId;
        PaymentMethod pm1 = PaymentMethod.builder().user(testUser).billingKey("dummy-1").cardIssuer("Visa").cardNumberMasked("1234-XXXX-XXXX-1111").isDefault(true).build();
        PaymentMethod pm2 = PaymentMethod.builder().user(testUser).billingKey("dummy-2").cardIssuer("MasterCard").cardNumberMasked("5678-XXXX-XXXX-2222").isDefault(false).build();
        List<PaymentMethod> paymentMethods = Arrays.asList(pm1, pm2);

        when(userRepository.existsById(userId)).thenReturn(true);
        when(paymentMethodRepository.findByUserId(userId)).thenReturn(paymentMethods);

        // When
        List<PaymentMethodResponse> result = paymentMethodService.getPaymentMethods(userId);

        // Then
        assertThat(result).isNotNull().hasSize(2);
        assertThat(result.get(0).cardIssuer()).isEqualTo("Visa");
        assertThat(result.get(1).cardIssuer()).isEqualTo("MasterCard");
        verify(paymentMethodRepository, times(1)).findByUserId(userId);
    }

    @Test
    @DisplayName("존재하지 않는 사용자 ID로 결제 수단 목록 조회 시 UserNotFoundException이 발생한다")
    void getPaymentMethods_withNonExistentUserId_throwsUserNotFoundException() {
        // Given
        Long userId = 999L;
        when(userRepository.existsById(userId)).thenReturn(false);

        // When & Then
        assertThatThrownBy(() -> paymentMethodService.getPaymentMethods(userId))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("User not found with ID: 999");

        verify(paymentMethodRepository, never()).findByUserId(anyLong());
    }

    @Test
    @DisplayName("유효한 사용자 ID와 결제 수단 ID로 결제 수단 삭제 시 성공한다")
    void deletePaymentMethod_withValidIds_success() {
        // Given
        Long userId = testId;
        Long methodId = 1L;
        PaymentMethod paymentMethod = PaymentMethod.builder().user(testUser).billingKey("dummy-1").cardIssuer("Visa").cardNumberMasked("1234-XXXX-XXXX-1111").isDefault(true).build();
        ReflectionTestUtils.setField(paymentMethod, "id", methodId);

        when(paymentMethodRepository.findById(methodId)).thenReturn(Optional.of(paymentMethod));
        doNothing().when(paymentMethodRepository).delete(paymentMethod);

        // When
        paymentMethodService.deletePaymentMethod(userId, methodId);

        // Then
        verify(paymentMethodRepository, times(1)).findById(methodId);
        verify(paymentMethodRepository, times(1)).delete(paymentMethod);
    }

    @Test
    @DisplayName("존재하지 않는 결제 수단 ID로 삭제 시 PaymentMethodNotFoundException이 발생한다")
    void deletePaymentMethod_withNonExistentMethodId_throwsPaymentMethodNotFoundException() {
        // Given
        Long userId = testId;
        Long methodId = 999L;

        when(paymentMethodRepository.findById(methodId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> paymentMethodService.deletePaymentMethod(userId, methodId))
                .isInstanceOf(PaymentMethodNotFoundException.class)
                .hasMessageContaining("Payment method not found with ID: 999");

        verify(paymentMethodRepository, never()).delete(any(PaymentMethod.class));
    }

    @Test
    @DisplayName("사용자 ID와 결제 수단 ID가 일치하지 않을 때 삭제 시 PaymentMethodNotFoundException이 발생한다")
    void deletePaymentMethod_withMismatchedUserId_throwsPaymentMethodNotFoundException() {
        // Given
        Long userId = testId;
        Long methodId = 1L;

        User anotherUser = User.builder()
                               .email("another@example.com")
                               .password("pass")
                               .username("Another")
                               .role("USER")
                               .phoneNumber("010")
                               .build();
        ReflectionTestUtils.setField(anotherUser, "id", 2L);

        PaymentMethod paymentMethod = PaymentMethod.builder().user(anotherUser).billingKey("dummy-1").cardIssuer("Visa").cardNumberMasked("1234-XXXX-XXXX-1111").isDefault(true).build();
        ReflectionTestUtils.setField(paymentMethod, "id", methodId);

        when(paymentMethodRepository.findById(methodId)).thenReturn(Optional.of(paymentMethod));

        // When & Then
        assertThatThrownBy(() -> paymentMethodService.deletePaymentMethod(userId, methodId))
                .isInstanceOf(PaymentMethodNotFoundException.class)
                .hasMessageContaining("Payment method not found for user ID: 1 and method ID: 1");

        verify(paymentMethodRepository, never()).delete(any(PaymentMethod.class));
    }

    @Test
    @DisplayName("유효한 사용자 ID와 결제 수단 ID로 기본 결제 수단 설정 시 성공한다")
    void setDefaultPaymentMethod_withValidIds_success() {
        // Given
        Long userId = testId;
        Long methodIdToSetDefault = 2L;

        PaymentMethod targetMethod = PaymentMethod.builder()
                                                  .user(testUser)
                                                  .isDefault(false)
                                                  .build();
        ReflectionTestUtils.setField(targetMethod, "id", methodIdToSetDefault);

        when(paymentMethodRepository.findById(methodIdToSetDefault)).thenReturn(Optional.of(targetMethod));
        doNothing().when(paymentMethodRepository).resetDefaultPaymentMethod(userId);

        // When
        paymentMethodService.setDefaultPaymentMethod(userId, methodIdToSetDefault);

        // Then
        assertThat(targetMethod.isDefault()).isTrue();
        verify(paymentMethodRepository).resetDefaultPaymentMethod(userId);
    }

    @Test
    @DisplayName("기본 결제 수단 설정 시 결제 수단 ID가 존재하지 않으면 예외 발생")
    void setDefaultPaymentMethod_noMethods_throwsException() {
        // Given
        Long userId = testId;
        Long methodId = 999L;

        when(paymentMethodRepository.findById(methodId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> paymentMethodService.setDefaultPaymentMethod(userId, methodId))
                .isInstanceOf(PaymentMethodNotFoundException.class)
                .hasMessageContaining("Payment method not found");
    }

    @Test
    @DisplayName("기본 결제 수단 설정 시 다른 사람의 결제 수단이면 예외 발생")
    void setDefaultPaymentMethod_notOwner_throwsException() {
        // Given
        Long userId = testId;
        Long otherMethodId = 2L;

        User anotherUser = User.builder()
                               .email("other@example.com")
                               .password("pass")
                               .username("Another")
                               .role("USER")
                               .phoneNumber("01099999999")
                               .build();
        ReflectionTestUtils.setField(anotherUser, "id", 99L);

        PaymentMethod otherMethod = PaymentMethod.builder().user(anotherUser).isDefault(false).build();
        ReflectionTestUtils.setField(otherMethod, "id", otherMethodId);

        when(paymentMethodRepository.findById(otherMethodId)).thenReturn(Optional.of(otherMethod));

        // When & Then
        assertThatThrownBy(() -> paymentMethodService.setDefaultPaymentMethod(userId, otherMethodId))
                .isInstanceOf(PaymentMethodNotFoundException.class)
                .hasMessageContaining("Payment method not found");

        verify(paymentMethodRepository, never()).resetDefaultPaymentMethod(anyLong());
    }

    @Test
    @DisplayName("기본 결제 수단 조회 시 Fetch Join 결과를 사용하여 반환한다")
    void getDefaultPaymentMethod_success() {
        // Given
        String userUuid = testUserUuid;
        PaymentMethod defaultMethod = PaymentMethod.builder()
                                                   .user(testUser)
                                                   .billingKey("billing-key")
                                                   .cardIssuer("Visa")
                                                   .expiryDate("12/25")
                                                   .cardNumberMasked("1234-****-5678")
                                                   .isDefault(true)
                                                   .build();
        ReflectionTestUtils.setField(defaultMethod, "id", 1L);
        ReflectionTestUtils.setField(defaultMethod, "paymentMethodId", "pm-123");

        when(paymentMethodRepository.findByUserUserIdAndIsDefaultTrue(userUuid))
                .thenReturn(Optional.of(defaultMethod));

        // When
        UserInfoForPaymentResponse response = paymentMethodService.getDefaultPaymentMethod(userUuid);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.userId()).isEqualTo(userUuid);
        assertThat(response.billingKey()).isEqualTo("billing-key");

        verify(paymentMethodRepository, times(1)).findByUserUserIdAndIsDefaultTrue(userUuid);
        verify(userRepository, never()).findById(anyLong());
    }
}