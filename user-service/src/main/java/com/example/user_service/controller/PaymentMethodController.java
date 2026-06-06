package com.example.user_service.controller;

import com.example.user_service.dto.request.PaymentMethodRegisterRequest;
import com.example.user_service.dto.response.PaymentMethodRegisterResponse;
import com.example.user_service.dto.response.PaymentMethodResponse;
import com.example.user_service.service.PaymentMethodService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/api/users/{userId}/payment-methods")
@RequiredArgsConstructor
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;

    @PostMapping
    public ResponseEntity<PaymentMethodRegisterResponse> registerPaymentMethod(
            @PathVariable Long userId,
            @Valid @RequestBody PaymentMethodRegisterRequest request) {
        PaymentMethodRegisterResponse response = paymentMethodService.registerPaymentMethod(userId, request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<PaymentMethodResponse>> getPaymentMethods(@PathVariable Long userId) {
        List<PaymentMethodResponse> paymentMethods = paymentMethodService.getPaymentMethods(userId);
        return ResponseEntity.ok(paymentMethods);
    }

    @DeleteMapping("/{methodId}")
    public ResponseEntity<Void> deletePaymentMethod(@PathVariable Long userId, @PathVariable Long methodId) {
        paymentMethodService.deletePaymentMethod(userId, methodId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{methodId}/default")
    public ResponseEntity<Void> setDefaultPaymentMethod(@PathVariable Long userId, @PathVariable Long methodId) {
        paymentMethodService.setDefaultPaymentMethod(userId, methodId);
        return ResponseEntity.noContent().build();
    }
}
