package weverse.serverC.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverC.dto.PaymentResultMessage;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.outbox.OutboxEventService;
import weverse.serverC.repository.FinalOrderRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final FinalOrderRepository finalOrderRepository;
    private final PgClient pgClient;
    private final OutboxEventService outboxEventService;

    private static final String RESULT_TOPIC = "payment-result";

    @Transactional
    public void processPayment(PurchaseMessage msg) {
        finalOrderRepository.claimOrders(List.of(msg));

        List<String> failedTraceIds = pgClient.processPayments(List.of(msg));
        boolean success = !failedTraceIds.contains(msg.traceId());

        String status = success ? "PAID" : "FAILED";
        finalOrderRepository.updateOrderStatus(List.of(msg.traceId()), status);

        // 결과 이벤트 저장 — 동일 트랜잭션 내 Outbox 저장
        String errorMsg = success ? null : "결제 거절";
        outboxEventService.save(msg.traceId(), RESULT_TOPIC,
                new PaymentResultMessage(msg.traceId(), success, errorMsg));
    }
}
