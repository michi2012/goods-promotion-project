package weverse.serverA.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import weverse.serverA.entity.Order;
import weverse.serverA.entity.OrderStatus;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByTraceId(String traceId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = :status WHERE o.traceId = :traceId AND o.status = OrderStatus.PENDING")
    int updateStatusIfPending(@Param("traceId") String traceId, @Param("status") OrderStatus status);
}
