package weverse.serverA.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_trace_id", columnList = "trace_id"),
                @Index(name = "idx_orders_status_created", columnList = "status, created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", unique = true, nullable = false, updatable = false)
    private String traceId;

    @Column(updatable = false)
    private Long userId;

    @Column(updatable = false)
    private Long goodsId;

    @Column(updatable = false)
    private int quantity;

    @Column(updatable = false)
    private String paymentMethod;

    @Column(updatable = false)
    private String shippingAddress;

    @Column(updatable = false)
    private String zipCode;

    @Column(updatable = false)
    private String phoneNumber;

    @Column(updatable = false)
    private String email;

    @Column(updatable = false)
    private String deliveryMemo;

    @Column(updatable = false)
    private String clientIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Builder
    public Order(String traceId, Long userId, Long goodsId, int quantity,
                 String paymentMethod, String shippingAddress, String zipCode,
                 String phoneNumber, String email, String deliveryMemo,
                 String clientIp, OrderStatus status) {
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

    public void markAsPaid() {
        this.status = OrderStatus.PAID;
    }

    public void markAsFailed() {
        this.status = OrderStatus.FAILED;
    }

    public void markAsExpired() {
        this.status = OrderStatus.EXPIRED;
    }
}
