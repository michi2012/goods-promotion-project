package weverse.serverA.entity;

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

    private String traceId;

    private Long goodsId;

    private int quantity;

    @Column(length = 1000)
    private String reason; // 에러 원인

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DltStatus status;

    @Builder
    public DeadLetter(String traceId, Long goodsId, int quantity, String reason) {
        this.traceId = traceId;
        this.goodsId = goodsId;
        this.quantity = quantity;
        this.reason = reason;
        this.status = DltStatus.UNRESOLVED;
    }

    public void markAsResolved() {
        this.status = DltStatus.RESOLVED;
    }
}