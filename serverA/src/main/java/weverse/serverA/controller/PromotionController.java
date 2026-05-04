package weverse.serverA.controller;

import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.dto.PurchaseRequest;
import weverse.serverA.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    @PostMapping("/purchase")
    public CompletableFuture<ResponseEntity<String>> purchase(@Valid @RequestBody PurchaseRequest request) {
        // TraceId 발급 후 Message로 변환
        String traceId = UUID.randomUUID().toString();
        PurchaseMessage message = PurchaseMessage.from(request, traceId);

        return promotionService.acceptPurchase(message);
    }

}