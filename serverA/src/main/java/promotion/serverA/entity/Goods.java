package promotion.serverA.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Goods {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    // 원 단위 가격. 원화는 소수가 없어 정수(long)로 저장한다.
    @Column(nullable = false)
    private long price;

    private int stock;

    @Builder
    public Goods(String name, long price, int stock) {
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

}