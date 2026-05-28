package weverse.serverC.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments", uniqueConstraints = {@UniqueConstraint(name = "uk_order_id", columnNames = {"order_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false, unique = true)
    private String orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "goods_id", nullable = false, updatable = false)
    private Long goodsId;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "payment_method", nullable = false, updatable = false)
    private String paymentMethod;

    @Column(name = "shipping_address", nullable = false, updatable = false)
    private String shippingAddress;

    @Column(name = "zip_code", nullable = false, updatable = false)
    private String zipCode;

    @Column(name = "phone_number", nullable = false, updatable = false) private String phoneNumber;
    @Column(nullable = false, updatable = false)
    private String email;

    @Column(name = "delivery_memo", updatable = false)
    private String deliveryMemo;

    @Column(name = "client_ip", updatable = false)
    private String clientIp;

    @Column(nullable = false, length = 50)
    private String status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Payment(String orderId, Long userId, Long goodsId, int quantity, String paymentMethod,
                      String shippingAddress, String zipCode, String phoneNumber, String email,
                      String deliveryMemo, String clientIp, String status) {
        this.orderId = orderId;
        this.userId = userId;
        this.goodsId = goodsId;
        this.quantity = quantity;
        this.paymentMethod = paymentMethod;
        this.shippingAddress = shippingAddress;
        this.zipCode = zipCode;
        this.phoneNumber = phoneNumber;
        this.email = email;
        this.deliveryMemo = deliveryMemo;
        this.clientIp = clientIp;
        this.status = status;
    }

}
