package weverse.serverA.repository;

import org.springframework.data.jpa.repository.Modifying;
import weverse.serverA.entity.Goods;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsRepository extends JpaRepository<Goods, Long> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Goods g SET g.stock = g.stock - :quantity WHERE g.id = :goodsId AND g.stock >= :quantity")
    int decreaseStockAtomically(@Param("goodsId") Long goodsId, @Param("quantity") int quantity);

    // Redis 검증 완료 후 사용하는 단순 차감 (WHERE 조건 없음)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Goods g SET g.stock = g.stock - :quantity WHERE g.id = :goodsId")
    void decreaseStock(@Param("goodsId") Long goodsId, @Param("quantity") int quantity);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Goods g SET g.stock = g.stock + :quantity WHERE g.id = :goodsId")
    int increaseStockAtomically(@Param("goodsId") Long goodsId, @Param("quantity") int quantity);

    @Query("SELECT g.stock FROM Goods g WHERE g.id = :goodsId")
    int findStockById(@Param("goodsId") Long goodsId);

}