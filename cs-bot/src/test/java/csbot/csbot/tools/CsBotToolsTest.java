package csbot.csbot.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import csbot.csbot.client.CsBotClient;
import csbot.csbot.client.dto.DltResponse;
import csbot.csbot.client.dto.GoodsResponse;
import csbot.csbot.client.dto.OrderStatusResponse;
import csbot.csbot.client.dto.PaymentResponse;
import csbot.csbot.client.dto.UserProfileResponse;
import csbot.csbot.classification.CsClassification;
import csbot.csbot.context.CsUserContext;
import csbot.csbot.linear.CsEscalationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import csbot.csbot.client.dto.RefundResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("CsBotTools 단위 테스트")
class CsBotToolsTest {

    @Mock
    private CsBotClient csBotClient;

    @Mock
    private CsUserContext csUserContext;

    @Mock
    private CsEscalationService escalationService;

    private CsBotTools csBotTools;

    @BeforeEach
    void setUp() {
        csBotTools = new CsBotTools(csBotClient, csUserContext, escalationService, new ObjectMapper());
    }

    @Test
    @DisplayName("getMyOrders: 주문 내역을 조회해 포맷팅된 문자열로 반환한다")
    void getMyOrders_성공() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 2, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));

        String result = csBotTools.getMyOrders();

        assertThat(result).contains("order-1").contains("PAID");
    }

    @Test
    @DisplayName("getMyOrders: 주문 내역이 없으면 안내 문구를 반환한다")
    void getMyOrders_빈목록() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of());

        String result = csBotTools.getMyOrders();

        assertThat(result).isEqualTo("조회된 주문 내역이 없습니다.");
    }

    @Test
    @DisplayName("getMyProfile: 회원 정보를 조회해 포맷팅된 문자열로 반환한다")
    void getMyProfile_성공() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csUserContext.getLoginId()).willReturn("test-uuid-123");
        given(csBotClient.getMyProfile(1L, "test-uuid-123")).willReturn(
                new UserProfileResponse(1L, "test-uuid-123", "test@example.com", "Test User", "01012345678")
        );

        String result = csBotTools.getMyProfile();

        assertThat(result).contains("Test User").contains("test@example.com").contains("01012345678");
    }

    @Test
    @DisplayName("requestRefund: serverA 환불 API를 로그인 ID로 호출하고, 접수되면 환불 처리 중으로 안내한다 (P-1)")
    void requestRefund_접수() {
        given(csUserContext.getLoginId()).willReturn("test-uuid-123");
        given(csBotClient.requestRefund("order-1", "test-uuid-123"))
                .willReturn(new RefundResponse("order-1", "REFUND_REQUESTED"));

        String result = csBotTools.requestRefund("order-1");

        assertThat(result).contains("order-1").contains("접수").contains("환불 처리 중");
        verify(csBotClient, never()).getMyPayments(any()); // 정책 판단은 serverA에서만
    }

    @Test
    @DisplayName("requestRefund: 이미 환불 완료된 주문이면 완료로 안내한다 (멱등)")
    void requestRefund_이미완료() {
        given(csUserContext.getLoginId()).willReturn("test-uuid-123");
        given(csBotClient.requestRefund("order-1", "test-uuid-123"))
                .willReturn(new RefundResponse("order-1", "REFUNDED"));

        assertThat(csBotTools.requestRefund("order-1")).contains("이미 완료");
    }

    @Test
    @DisplayName("requestRefund: serverA가 거절하면(409 기간 초과) 응답 본문의 문구를 그대로 안내한다")
    void requestRefund_거절문구전달() {
        given(csUserContext.getLoginId()).willReturn("test-uuid-123");
        byte[] body = """
                {"status":409,"error":"REFUND_PERIOD_EXPIRED","message":"환불 기간(결제 후 7일)이 지났습니다"}
                """.getBytes(StandardCharsets.UTF_8);
        given(csBotClient.requestRefund("order-1", "test-uuid-123")).willThrow(
                HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null, body, StandardCharsets.UTF_8));

        String result = csBotTools.requestRefund("order-1");

        assertThat(result).contains("거절").contains("환불 기간(결제 후 7일)이 지났습니다");
    }

    @Test
    @DisplayName("requestRefund: 거절 응답에 본문이 없으면 일반 문구로 안내한다")
    void requestRefund_거절본문없음() {
        given(csUserContext.getLoginId()).willReturn("test-uuid-123");
        given(csBotClient.requestRefund("order-1", "test-uuid-123")).willThrow(
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, new byte[0], StandardCharsets.UTF_8));

        assertThat(csBotTools.requestRefund("order-1")).contains("거절").contains("잠시 후 다시 시도해주세요");
    }

    @Test
    @DisplayName("escalateToHuman: CsEscalationService에 위임한다")
    void escalateToHuman_위임() {
        given(csUserContext.getLoginId()).willReturn("test-uuid-123");
        given(csUserContext.getUrgency()).willReturn(CsClassification.Urgency.MEDIUM);
        given(csUserContext.getConversationId()).willReturn("conv-1");
        given(csUserContext.getOriginalMessage()).willReturn("원본 메시지");
        given(escalationService.createEscalationTicket(
                "문의 요약", "test-uuid-123", CsClassification.Urgency.MEDIUM, "conv-1", "원본 메시지"))
                .willReturn("상담원에게 문의가 접수되었습니다.");

        String result = csBotTools.escalateToHuman("문의 요약");

        assertThat(result).isEqualTo("상담원에게 문의가 접수되었습니다.");
    }

    @Test
    @DisplayName("getOrderDetail: 본인 주문의 상세 정보(결제+상품명+주문상태)를 통합해 반환한다")
    void getOrderDetail_성공() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 2, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));
        given(csBotClient.getGoodsInfo(10L)).willReturn(new GoodsResponse(10L, "프로모션 상품", 50));
        given(csBotClient.getOrderStatus("order-1")).willReturn(new OrderStatusResponse("order-1", "COMPLETED"));

        String result = csBotTools.getOrderDetail("order-1");

        assertThat(result).contains("order-1").contains("프로모션 상품").contains("PAID").contains("COMPLETED");
    }

    @Test
    @DisplayName("getOrderDetail: 본인 주문이 아니면 거부 메시지를 반환한다")
    void getOrderDetail_본인주문아님() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-2", 1L, 10L, 2, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));

        String result = csBotTools.getOrderDetail("order-1");

        assertThat(result).contains("찾을 수 없습니다");
    }

    @Test
    @DisplayName("trackRefundStatus: 본인 주문이고 serverB 상태가 REFUNDED면 환불 완료로 안내한다")
    void trackRefundStatus_완료() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 2, "CARD", "CANCELLED", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));
        given(csBotClient.getOrderStatus("order-1")).willReturn(new OrderStatusResponse("order-1", "REFUNDED"));

        assertThat(csBotTools.trackRefundStatus("order-1")).contains("환불이 완료");
    }

    @Test
    @DisplayName("trackRefundStatus: serverB 상태가 REFUND_REQUESTED면 처리 중으로 안내하고 재요청을 권하지 않는다")
    void trackRefundStatus_처리중() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 2, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));
        given(csBotClient.getOrderStatus("order-1")).willReturn(new OrderStatusResponse("order-1", "REFUND_REQUESTED"));

        String result = csBotTools.trackRefundStatus("order-1");

        assertThat(result).contains("환불 처리 중").doesNotContain("다시 시도");
    }

    @Test
    @DisplayName("trackRefundStatus: 본인 주문이 아니면 주문 상태를 조회하지 않는다")
    void trackRefundStatus_본인주문아님() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-2", 1L, 10L, 2, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));

        assertThat(csBotTools.trackRefundStatus("order-1")).contains("찾을 수 없습니다");
        verify(csBotClient, never()).getOrderStatus(any());
    }

    @Test
    @DisplayName("diagnosePaymentIssue: 동일 goodsId 10분 이내 2건 결제 시 중복 감지를 반환한다")
    void diagnosePaymentIssue_중복결제감지() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 1, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0)),
                new PaymentResponse("order-2", 1L, 10L, 1, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 5))
        ));

        String result = csBotTools.diagnosePaymentIssue();

        assertThat(result).contains("중복 결제 감지");
    }

    @Test
    @DisplayName("diagnosePaymentIssue: 이상 징후가 없으면 정상 메시지를 반환한다")
    void diagnosePaymentIssue_이상없음() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 1, "CARD", "CANCELLED", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));

        String result = csBotTools.diagnosePaymentIssue();

        assertThat(result).contains("이상 징후가 감지되지 않았습니다");
    }

    @Test
    @DisplayName("checkAndRetryPurchaseDlt: UNRESOLVED DLT 발견 시 retryDlt를 호출하고 재처리 완료 메시지를 반환한다")
    void checkAndRetryPurchaseDlt_재처리성공() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 1, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));
        given(csBotClient.getDltByOrderId("order-1")).willReturn(
                new DltResponse(5L, "order-1", 10L, 1, "재고 부족", "UNRESOLVED")
        );

        String result = csBotTools.checkAndRetryPurchaseDlt("order-1");

        assertThat(result).contains("재처리를 완료");
        verify(csBotClient).retryDlt(5L);
    }

    @Test
    @DisplayName("checkAndRetryPurchaseDlt: DLT가 없으면 정상 처리 메시지를 반환한다")
    void checkAndRetryPurchaseDlt_DLT없음() {
        given(csUserContext.resolveNumericId()).willReturn(1L);
        given(csBotClient.getMyPayments(1L)).willReturn(List.of(
                new PaymentResponse("order-1", 1L, 10L, 1, "CARD", "PAID", LocalDateTime.of(2026, 6, 1, 10, 0))
        ));
        given(csBotClient.getDltByOrderId("order-1")).willThrow(new RuntimeException("404 Not Found"));

        String result = csBotTools.checkAndRetryPurchaseDlt("order-1");

        assertThat(result).contains("정상");
        verify(csBotClient, never()).retryDlt(any());
    }
}
