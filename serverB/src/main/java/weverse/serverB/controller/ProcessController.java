package weverse.serverB.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import weverse.serverB.dto.OrderStatusResponse;
import weverse.serverB.dto.PurchaseMessage;
import weverse.serverB.dto.StockResponse;
import weverse.serverB.exception.SystemOverloadException;
import weverse.serverB.service.PipelineService;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/v1/b")
@RequiredArgsConstructor
@Slf4j
public class ProcessController {

    private final AtomicInteger currentInFlightRequests = new AtomicInteger(0);
    private static final int MAX_IN_FLIGHT = 20000;

    private final PipelineService pipelineService;

    @PostMapping("/purchases/bulk")
    public ResponseEntity<?> receiveBulk(@RequestBody List<PurchaseMessage> messages) {
        if (messages.isEmpty()) return ResponseEntity.ok("Empty");

        if (currentInFlightRequests.addAndGet(messages.size()) > MAX_IN_FLIGHT) {
            currentInFlightRequests.addAndGet(-messages.size());
            throw new SystemOverloadException("Server B Capacity Exceeded");
        }

        try {
            pipelineService.processBulkData(messages);
            return ResponseEntity.ok("Accepted");
        } finally {
            currentInFlightRequests.addAndGet(-messages.size());
        }
    }

    // 서버 A -> 서버 B : 품절 이벤트 수신
    @PostMapping("/goods/{goodsId}/sold-out")
    public ResponseEntity<String> markAsSoldOut(@PathVariable Long goodsId) {
        pipelineService.markGoodsAsSoldOut(goodsId);
        return ResponseEntity.ok("Sold out event processed");
    }

    // 프론트엔드 -> 서버 B : 실시간 재고 조회
    @GetMapping("/goods/{goodsId}/stock")
    public ResponseEntity<StockResponse> getRemainingStock(@PathVariable Long goodsId) {
        int stock = pipelineService.getRemainingStock(goodsId);
        boolean isSoldOut = (stock <= 0);
        return ResponseEntity.ok(new StockResponse(goodsId, stock, isSoldOut));
    }

    // 프론트엔드 -> 서버 B : 내 주문 상태 확인
    @GetMapping("/users/{userId}/order-status")
    public ResponseEntity<OrderStatusResponse> getOrderStatus(@PathVariable Long userId) {
        String status = pipelineService.getUserOrderStatus(userId);
        return ResponseEntity.ok(new OrderStatusResponse(String.valueOf(userId), status));
    }
}