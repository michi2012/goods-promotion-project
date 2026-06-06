package com.example.user_service.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "payment_method")
public class PaymentMethod extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, unique = true)
    private String paymentMethodId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "billing_key", nullable = false, length = 255)
    private String billingKey;

    @Column(name = "card_issuer", nullable = false, length = 50)
    private String cardIssuer;

    @Column(name = "expiry_date", nullable = false, length = 5)
    private String expiryDate;

    @Column(name = "card_number_masked", nullable = false, length = 50)
    private String cardNumberMasked;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Builder
    public PaymentMethod(User user, String billingKey, String cardIssuer, String expiryDate, String cardNumberMasked, boolean isDefault) {
        this.paymentMethodId = "pm-" + UUID.randomUUID();
        this.user = user;
        this.billingKey = billingKey;
        this.cardIssuer = cardIssuer;
        this.expiryDate = expiryDate;
        this.cardNumberMasked = cardNumberMasked;
        this.isDefault = isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public void setUser(User user) {
        this.user = user;
    }
}
