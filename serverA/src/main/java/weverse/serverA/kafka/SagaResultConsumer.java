package weverse.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import weverse.serverA.dto.PaymentResultMessage;
import weverse.serverA.dto.StatusUpdateResultMessage;
import weverse.serverA.service.SagaOrchestratorService;
import weverse.serverA.service.SagaStateService;

@Component
@RequiredArgsConstructor
@Slf4j
public class SagaResultConsumer {

    private final SagaStateService sagaStateService;
    private final SagaOrchestratorService sagaOrchestratorService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "status-update-result", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeStatusUpdateResult(String payload) throws Exception {
        StatusUpdateResultMessage msg = objectMapper.readValue(payload, StatusUpdateResultMessage.class);
        log.info("[Saga] status-update-result 수신: traceId={}, success={}", msg.traceId(), msg.success());

        if (!msg.success()) {
            sagaOrchestratorService.handleSagaFailure(msg.traceId(), msg.errorMessage());
            return;
        }

        boolean bothDone = sagaStateService.markStatusUpdateCompleted(msg.traceId());
        if (bothDone) {
            sagaOrchestratorService.tryCompleteSaga(msg.traceId());
        }
    }

    @KafkaListener(topics = "payment-result", groupId = "${spring.kafka.consumer.group-id}")
    public void consumePaymentResult(String payload) throws Exception {
        PaymentResultMessage msg = objectMapper.readValue(payload, PaymentResultMessage.class);
        log.info("[Saga] payment-result 수신: traceId={}, success={}", msg.traceId(), msg.success());

        if (!msg.success()) {
            sagaOrchestratorService.handleSagaFailure(msg.traceId(), msg.errorMessage());
            return;
        }

        boolean bothDone = sagaStateService.markPaymentCompleted(msg.traceId());
        if (bothDone) {
            sagaOrchestratorService.tryCompleteSaga(msg.traceId());
        }
    }
}
