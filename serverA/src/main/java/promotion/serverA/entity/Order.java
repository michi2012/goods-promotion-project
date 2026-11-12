package promotion.serverA.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "orders",
        indexes = {
                @Index(name = "idx_orders_order_id", columnList = "order_id"),
                @Index(name = "idx_orders_status_created", columnList = "status, created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", unique = true, nullable = false, updatable = false)
    private String orderId;

    @Column(updatable = false)
    private Long userId;

    @Column(updatable = false)
    private Long goodsId;

    @Column(updatable = false)
    private int quantity;

    // 주문 시점 가격 스냅샷 (원 단위). 이후 상품 가격이 바뀌어도 이 주문의 금액은 바뀌지 않는다.
    // 금액 도입 이전 주문은 null.
    @Column(updatable = false)
    private Long unitPrice;

    @Column(updatable = false)
    private Long totalAmount;

    @Column(updatable = false, length = 3)
    private String currency;

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

    @Column(updatable = false, nullable = false)
    private String clientIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Builder
    public Order(String orderId, Long userId, Long goodsId, int quantity,
                 Long unitPrice, Long totalAmount, String currency,
                 String paymentMethod, String shippingAddress, String zipCode,
                 String phoneNumber, String email, String deliveryMemo,
                 String clientIp, OrderStatus status) {
        this.orderId = orderId;
        this.userId = userId;
        this.goodsId = goodsId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.totalAmount = totalAmount;
        this.currency = currency;
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
