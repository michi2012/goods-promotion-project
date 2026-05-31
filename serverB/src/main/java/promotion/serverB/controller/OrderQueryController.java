package promotion.serverB.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import promotion.serverB.dto.OrderStatusResponse;
import promotion.serverB.service.OrderQueryService;

@RestController
@RequiredArgsConstructor
public class OrderQueryController {

    private final OrderQueryService orderQueryService;

    @GetMapping("/api/v1/orders/{orderId}/status")
    public ResponseEntity<OrderStatusResponse> getOrderStatus(@PathVariable String orderId) {
        String status = orderQueryService.getOrderStatus(orderId);
        return ResponseEntity.ok(new OrderStatusResponse(orderId, status));
    }

    @GetMapping("/api/v1/goods/{goodsId}/stock")
    public ResponseEntity<Long> getStockView(@PathVariable Long goodsId) {
        return ResponseEntity.ok(orderQueryService.getStockView(goodsId));
    }
}
