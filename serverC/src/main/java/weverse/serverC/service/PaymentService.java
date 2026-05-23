package weverse.serverC.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import weverse.serverC.dto.PaymentResultMessage;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.repository.FinalOrderRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final FinalOrderRepository finalOrderRepository;
    private final PgClient pgClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final String RESULT_TOPIC = "payment-result";

    public void processPayment(PurchaseMessage msg) throws Exception {
        finalOrderRepository.claimOrders(List.of(msg));

        List<String> failedTraceIds = pgClient.processPayments(List.of(msg));
        boolean success = !failedTraceIds.contains(msg.traceId());

        // 상태 업데이트
        String status = success ? "PAID" : "FAILED";
        finalOrderRepository.updateOrderStatus(List.of(msg.traceId()), status);

        // 결과 발행
        String errorMsg = success ? null : "결제 거절";
        String result = objectMapper.writeValueAsString(
                new PaymentResultMessage(msg.traceId(), success, errorMsg));
        kafkaTemplate.send(RESULT_TOPIC, msg.traceId(), result);
    }
}
