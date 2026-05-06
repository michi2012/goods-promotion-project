package weverse.serverC.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "final_order", uniqueConstraints = {@UniqueConstraint(name = "uk_trace_id", columnNames = {"trace_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class FinalOrder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, updatable = false, unique = true)
    private String traceId;

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
    public FinalOrder(String traceId, Long userId, Long goodsId, int quantity, String paymentMethod,
                      String shippingAddress, String zipCode, String phoneNumber, String email,
                      String deliveryMemo, String clientIp, String status) {
        this.traceId = traceId;
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