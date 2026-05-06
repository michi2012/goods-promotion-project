package weverse.serverC.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import weverse.serverC.repository.FinalOrderRepository;

@RestController
@RequestMapping("/api/v1/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final FinalOrderRepository finalOrderRepository;

    @GetMapping("/{traceId}")
    public ResponseEntity<Boolean> isPaymentExisted(@PathVariable String traceId) {
        // final_order 테이블에 해당 traceId가 있으면 이미 성공한 결제임
        boolean exists = finalOrderRepository.existsByTraceId(traceId);
        return ResponseEntity.ok(exists);
    }
}
