package promotion.serverA.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import promotion.serverA.entity.Order;
import promotion.serverA.entity.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderId(String orderId);

    // Redis Saga 상태 유실 의심 PENDING 주문 재대사용 — idx_orders_status_created 인덱스 활용
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime createdAt);

    // 결과 이벤트 유실 의심 종료(EXPIRED/FAILED) 주문 재대사용 — idx_orders_status_created 인덱스 활용
    List<Order> findByStatusInAndCreatedAtBetween(List<OrderStatus> statuses, LocalDateTime from, LocalDateTime to);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = :status WHERE o.orderId = :orderId AND o.status = OrderStatus.PENDING")
    int updateStatusIfPending(@Param("orderId") String orderId, @Param("status") OrderStatus status);

    // PENDING → PAID 전이 + 결제 완료 시각 기록 (환불 기간 판단용). 조건부라 멱등이다.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = OrderStatus.PAID, o.paidAt = :paidAt WHERE o.orderId = :orderId AND o.status = OrderStatus.PENDING")
    int markPaidIfPending(@Param("orderId") String orderId, @Param("paidAt") LocalDateTime paidAt);

    // 현재 상태가 from일 때만 to로 바꾼다 (환불 PAID→REFUND_REQUESTED→REFUNDED). 반환값 1이면 이 호출이 전이시킨 것.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = :to WHERE o.orderId = :orderId AND o.status = :from")
    int updateStatusIfCurrent(@Param("orderId") String orderId, @Param("from") OrderStatus from, @Param("to") OrderStatus to);
}
