package promotion.serverA.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderId(String orderId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = :status WHERE o.orderId = :orderId AND o.status = OrderStatus.PENDING")
    int updateStatusIfPending(@Param("orderId") String orderId, @Param("status") OrderStatus status);
}
