package weverse.serverA.controller;

import lombok.extern.slf4j.Slf4j;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.dto.request.PurchaseRequest;
import weverse.serverA.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
@Slf4j
public class PromotionController {

    private final PromotionService promotionService;

    @PostMapping("/purchase")
    public ResponseEntity<String> purchase(@Valid @RequestBody PurchaseRequest request) {
        String traceId = UUID.randomUUID().toString();
        log.info("[주문 수신] TraceId: {} | UserId: {} | GoodsId: {} | 결제수단: {}",
                traceId, request.userId(), request.goodsId(), request.paymentMethod());
        PurchaseMessage message = PurchaseMessage.from(request, traceId);

        promotionService.acceptPurchase(message);

        return ResponseEntity.accepted()
                             .body("처리 대기중입니다. 잠시 뒤 확인해주세요.");
    }

}