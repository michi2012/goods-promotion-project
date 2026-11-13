package promotion.serverA.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import promotion.serverA.dto.PaymentResultMessage;
import promotion.serverA.dto.StatusUpdateResultMessage;
import promotion.serverA.service.SagaOrchestratorService;
import promotion.serverA.service.SagaStateService;

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
        log.info("[Saga] status-update-result 수신: orderId={}, success={}", msg.orderId(), msg.success());

        if (!msg.success()) {
            sagaOrchestratorService.handleSagaFailure(msg.orderId(), msg.errorMessage());
            return;
        }

        // Saga가 이미 끝났으면(상태 삭제) 플래그를 기록하지 않는다 — 없는 키에 HSET하면 좀비 키가 생긴다.
        if (!sagaStateService.exists(msg.orderId())) {
            log.info("[Saga] 종료된 Saga의 status-update-result 무시: orderId={}", msg.orderId());
            return;
        }

        boolean bothDone = sagaStateService.markStatusUpdateCompleted(msg.orderId());
        if (bothDone) {
            sagaOrchestratorService.tryCompleteSaga(msg.orderId());
        }
    }

    @KafkaListener(topics = "payment-result", groupId = "${spring.kafka.consumer.group-id}")
    public void consumePaymentResult(String payload) throws Exception {
        PaymentResultMessage msg = objectMapper.readValue(payload, PaymentResultMessage.class);
        log.info("[Saga] payment-result 수신: orderId={}, success={}", msg.orderId(), msg.success());

        if (!msg.success()) {
            sagaOrchestratorService.handleSagaFailure(msg.orderId(), msg.errorMessage());
            return;
        }

        // Saga가 이미 끝난 뒤 도착한 결제 성공 — 주문이 실패로 확정됐다면 망취소가 필요하다.
        if (!sagaStateService.exists(msg.orderId())) {
            sagaOrchestratorService.compensateLatePaymentSuccess(msg.orderId());
            return;
        }

        boolean bothDone = sagaStateService.markPaymentCompleted(msg.orderId());
        if (bothDone) {
            sagaOrchestratorService.tryCompleteSaga(msg.orderId());
        }
    }
}
