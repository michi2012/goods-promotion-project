package promotion.serverA.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import promotion.serverA.service.RefundService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentCancelledConsumerTest {

    @Mock
    private RefundService refundService;

    private SimpleMeterRegistry meterRegistry;
    private PaymentCancelledConsumer consumer;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new PaymentCancelledConsumer(refundService, new ObjectMapper(), meterRegistry);
        consumer.initMetrics();
    }

    @Test
    @DisplayName("payment-cancelled를 받으면 orderId로 환불 완료를 요청한다 (cancelledAt 등 다른 필드는 무시)")
    void consume_delegatesToRefundService() throws Exception {
        consumer.consume("{\"orderId\":\"order-1\",\"cancelledAt\":\"2026-11-16T12:00:00\"}");

        verify(refundService).completeRefund("order-1");
    }

    @Test
    @DisplayName("JSON이 깨지면 JsonProcessingException을 던져 재시도 없이 DLT로 보낸다")
    void consume_invalidJson() {
        assertThatThrownBy(() -> consumer.consume("{bad"))
                .isInstanceOf(JsonProcessingException.class);
        verify(refundService, never()).completeRefund(anyString());
    }

    @Test
    @DisplayName("DLT 도착은 예외 없이 실패 메트릭만 올린다 (주문은 REFUND_REQUESTED로 남음)")
    void handleDlt_incrementsMetric() {
        consumer.handleDlt("{\"orderId\":\"order-1\"}", "DB 장애");

        assertThat(meterRegistry.counter("business_refund_complete_failed_total").count()).isEqualTo(1.0);
        verify(refundService, never()).completeRefund(anyString());
    }
}
