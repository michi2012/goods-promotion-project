package weverse.serverC.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.dto.ServerCResponse;
import weverse.serverC.service.OrderProcessingService;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/api/v1/c")
@RequiredArgsConstructor
public class OrderController {
    private final OrderProcessingService orderProcessingService;

    @PostMapping("/orders/bulk")
    public ResponseEntity<ServerCResponse> receiveBulkOrders(@RequestBody List<PurchaseMessage> messages) {
        log.info("[OrderController] 벌크 주문 요청 수신: 건수 = {}", (messages != null ? messages.size() : 0));

        if (messages == null || messages.isEmpty()) {
            return ResponseEntity.ok(new ServerCResponse(true, "No Data", List.of()));
        }

        // 예외가 터지지 않고, 성공/실패 목록이 담긴 객체를 반환받음
        ServerCResponse response = orderProcessingService.processBulkOrders(messages);
        log.info("[OrderController] 벌크 주문 처리 완료: 실패 건수 = {}", response.getFailedTraceIds().size());
        return ResponseEntity.ok(response);
    }
}