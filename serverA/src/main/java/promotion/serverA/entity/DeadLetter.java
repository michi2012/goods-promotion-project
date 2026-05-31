package promotion.serverA.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderId;

    private Long goodsId;

    private int quantity;

    @Column(length = 1000)
    private String reason; // 에러 원인

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DltStatus status;

    @Builder
    public DeadLetter(String orderId, Long goodsId, int quantity, String reason) {
        this.orderId = orderId;
        this.goodsId = goodsId;
        this.quantity = quantity;
        this.reason = reason;
        this.status = DltStatus.UNRESOLVED;
    }

    public void markAsResolved() {
        this.status = DltStatus.RESOLVED;
    }
}