package weverse.serverA.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Goods {

    @Id
    private Long id;

    private String name;

    private int stock;

    public Goods(Long id, String name, int stock) {
        this.id = id;
        this.name = name;
        this.stock = stock;
    }

}