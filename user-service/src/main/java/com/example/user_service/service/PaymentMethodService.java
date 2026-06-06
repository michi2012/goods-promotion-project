package com.example.user_service.service;

import com.example.user_service.dto.request.PaymentMethodRegisterRequest;
import com.example.user_service.dto.response.PaymentMethodRegisterResponse;
import com.example.user_service.dto.response.PaymentMethodResponse;
import com.example.user_service.dto.response.UserInfoForPaymentResponse;
import com.example.user_service.entity.PaymentMethod;
import com.example.user_service.entity.User;
import com.example.user_service.exception.UserNotFoundException;
import com.example.user_service.exception.PaymentMethodNotFoundException;
import com.example.user_service.repository.PaymentMethodRepository;
import com.example.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentMethodService {

    private final PaymentMethodRepository paymentMethodRepository;
    private final UserRepository userRepository;

    @Transactional
    public PaymentMethodRegisterResponse registerPaymentMethod(Long userId, PaymentMethodRegisterRequest request) {
        log.info("사용자 ID: {} 에 대한 결제 수단 등록을 시도합니다.", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("결제 수단 등록 실패: ID {} 에 해당하는 사용자를 찾을 수 없습니다.", userId);
                    return new UserNotFoundException("User not found with ID: " + userId);
                });

        // 1. [가상 로직] 실제 PG사 연동 대신, billing_key에 dummy-billing-key-UUID 형식의 가상 키를 생성합니다.
        String billingKey = "dummy-billing-key-" + UUID.randomUUID().toString();

        // 2. [가상 로직] card_number를 기반으로 카드사를 추정하고, 앞/뒤 일부를 제외하고 마스킹 처리합니다.
        String cardIssuer = inferCardIssuer(request.cardNumber()); // Simple inference
        String cardNumberMasked = maskCardNumber(request.cardNumber());

        boolean isDefault = !paymentMethodRepository.existsByUserId(userId);

        PaymentMethod paymentMethod = PaymentMethod.builder()
                .user(user)
                .billingKey(billingKey)
                .cardIssuer(cardIssuer)
                .expiryDate(request.expiryDate())
                .cardNumberMasked(cardNumberMasked)
                .isDefault(isDefault)
                .build();

        user.addPaymentMethod(paymentMethod);

        PaymentMethod savedPaymentMethod = paymentMethodRepository.save(paymentMethod);
        log.info("사용자 ID: {} 에 대한 결제 수단 ID: {} 등록 성공.", userId, savedPaymentMethod.getId());

        return PaymentMethodRegisterResponse.builder()
                .id(savedPaymentMethod.getId())
                .cardIssuer(savedPaymentMethod.getCardIssuer())
                .cardNumberMasked(savedPaymentMethod.getCardNumberMasked())
                .isDefault(savedPaymentMethod.isDefault())
                .build();
    }

    @Transactional(readOnly = true)
    public List<PaymentMethodResponse> getPaymentMethods(Long userId) {
        log.info("사용자 ID: {} 에 대한 결제 수단 목록 조회를 시도합니다.", userId);

        if (!userRepository.existsById(userId)) {
            log.warn("결제 수단 목록 조회 실패: ID {} 에 해당하는 사용자를 찾을 수 없습니다.", userId);
            throw new UserNotFoundException("User not found with ID: " + userId);
        }

        return paymentMethodRepository.findByUserId(userId).stream()
                .map(pm -> PaymentMethodResponse.builder()
                        .id(pm.getId())
                        .cardIssuer(pm.getCardIssuer())
                        .cardNumberMasked(pm.getCardNumberMasked())
                        .isDefault(pm.isDefault())
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void deletePaymentMethod(Long userId, Long methodId) {
        log.info("사용자 ID: {} 의 결제 수단 ID: {} 삭제를 시도합니다.", userId, methodId);

        PaymentMethod paymentMethod = paymentMethodRepository.findById(methodId)
                .orElseThrow(() -> {
                    log.warn("결제 수단 삭제 실패: ID {} 에 해당하는 결제 수단을 찾을 수 없습니다.", methodId);
                    return new PaymentMethodNotFoundException("Payment method not found with ID: " + methodId);
                });

        if (!paymentMethod.getUser().getId().equals(userId)) {
            log.warn("결제 수단 삭제 실패: 사용자 ID {} 가 결제 수단 ID {} 의 소유자가 아닙니다.", userId, methodId);
            throw new PaymentMethodNotFoundException("Payment method not found for user ID: " + userId + " and method ID: " + methodId);
        }

        paymentMethodRepository.delete(paymentMethod);
        log.info("사용자 ID: {} 의 결제 수단 ID: {} 삭제 성공.", userId, methodId);
    }

    @Transactional
    public void setDefaultPaymentMethod(Long userId, Long methodId) {
        log.info("사용자 ID: {} 의 결제 수단 ID: {} 를 기본 결제 수단으로 설정을 시도합니다.", userId, methodId);

        PaymentMethod target = paymentMethodRepository.findById(methodId)
                .orElseThrow(() -> {
                    log.warn("기본 결제 수단 설정 실패: 사용자 ID {} 에 해당하는 결제 수단 ID {} 를 찾을 수 없습니다.", userId, methodId);
                    return new PaymentMethodNotFoundException(
                            "Payment method not found for user ID: " + userId + " and method ID: " + methodId);
                });

        if (!target.getUser().getId().equals(userId)) {
            log.warn("기본 결제 수단 설정 실패: 사용자 ID {} 가 결제 수단 ID {} 의 소유자가 아닙니다.", userId, methodId);
            throw new PaymentMethodNotFoundException(
                    "Payment method not found for user ID: " + userId + " and method ID: " + methodId);
        }

        // resetDefaultPaymentMethod는 clearAutomatically=true이므로 컨텍스트 초기화 후 재조회
        paymentMethodRepository.resetDefaultPaymentMethod(userId);
        paymentMethodRepository.findById(methodId).ifPresent(pm -> pm.setDefault(true));
        log.info("사용자 ID: {} 의 결제 수단 ID: {} 가 기본 결제 수단으로 설정되었습니다.", userId, methodId);
    }

    @Transactional(readOnly = true)
    public UserInfoForPaymentResponse getDefaultPaymentMethod(String userId) {
        log.info("사용자 ID: {} 의 기본 결제 수단 조회를 시도합니다.", userId);

        PaymentMethod paymentMethod = paymentMethodRepository.findByUserUserIdAndIsDefaultTrue(userId)
                .orElseThrow(() -> {
                    log.warn("기본 결제 수단 조회 실패: 사용자 ID {} 에 해당하는 기본 결제 수단이 없습니다.", userId);
                    return new PaymentMethodNotFoundException("No default payment method found for user ID: " + userId);
                });

        User user = paymentMethod.getUser();
        log.info("사용자 ID: {} 의 기본 결제 수단 조회 성공. 결제 수단 ID: {}", userId, paymentMethod.getId());

        return new UserInfoForPaymentResponse(user.getUserId(), user.getUsername(), user.getEmail(), paymentMethod.getPaymentMethodId(), paymentMethod.getBillingKey());
    }

    private String inferCardIssuer(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) {
            return "Unknown";
        }
        if (cardNumber.startsWith("4")) {
            return "Visa";
        }
        if (cardNumber.startsWith("5")) {
            return "MasterCard";
        }
        if (cardNumber.startsWith("34") || cardNumber.startsWith("37")) {
            return "American Express";
        }
        return "Other";
    }

    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 10) {
            return cardNumber;
        }
        return cardNumber.substring(0, 4) + "-" +
               "XXXX-XXXX" + "-" +
               cardNumber.substring(cardNumber.length() - 4);
    }
}
