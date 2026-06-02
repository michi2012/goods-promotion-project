package promotion.serverA.controller;

import com.fasterxml.uuid.Generators;
import lombok.extern.slf4j.Slf4j;
import promotion.serverA.dto.PurchaseMessage;
import promotion.serverA.dto.request.PurchaseRequest;
import promotion.serverA.dto.response.PurchaseResponse;
import promotion.serverA.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
@Slf4j
public class PromotionController {

    private final PromotionService promotionService;

    @PostMapping("/purchase")
    public ResponseEntity<PurchaseResponse> purchase(@Valid @RequestBody PurchaseRequest request) {
        String orderId = Generators.timeBasedEpochGenerator().generate().toString();

        log.info("[주문 수신] TraceId: {} | UserId: {} | GoodsId: {} | 결제수단: {}",
                orderId, request.userId(), request.goodsId(), request.paymentMethod());

        PurchaseMessage message = PurchaseMessage.from(request, orderId);

        promotionService.acceptPurchase(message);

        return ResponseEntity.accepted()
                             .body(new PurchaseResponse(orderId, "처리 대기중입니다. 잠시 뒤 확인해주세요."));
    }

}