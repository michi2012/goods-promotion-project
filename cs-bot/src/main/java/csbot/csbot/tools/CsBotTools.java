package csbot.csbot.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import csbot.csbot.client.CsBotClient;
import csbot.csbot.client.dto.DltResponse;
import csbot.csbot.client.dto.GoodsResponse;
import csbot.csbot.client.dto.OrderStatusResponse;
import csbot.csbot.client.dto.PaymentCancelMessage;
import csbot.csbot.client.dto.PaymentResponse;
import csbot.csbot.client.dto.UserProfileResponse;
import csbot.csbot.context.CsUserContext;
import csbot.csbot.linear.CsEscalationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CsBotTools {

    private static final String PAYMENT_CANCEL_TOPIC = "payment-cancel";
    private static final String CANCELABLE_STATUS = "PAID";

    private final CsBotClient csBotClient;
    private final CsUserContext csUserContext;
    private final CsEscalationService escalationService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Tool(description = """
            고객 본인의 구매/결제 내역과 환불·취소 상태를 조회합니다.
            언제 호출: 주문 내역, 결제 상태, 결제 실패 사유, 환불/취소 진행 상태 등 본인 주문 관련 문의에 호출.
            반환: 주문번호, 상품ID, 수량, 결제수단, 상태(PAID/FAILED), 주문일시 목록.
            """)
    public String getMyOrders() {
        try {
            List<PaymentResponse> payments = csBotClient.getMyPayments(csUserContext.resolveNumericId());
            if (payments.isEmpty()) {
                return "조회된 주문 내역이 없습니다.";
            }
            return payments.stream()
                    .map(p -> "주문번호: %s, 상품ID: %d, 수량: %d, 결제수단: %s, 상태: %s, 주문일시: %s"
                            .formatted(p.orderId(), p.goodsId(), p.quantity(), p.paymentMethod(), p.status(), p.createdAt()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("[CsBotTools] 주문 내역 조회 실패: {}", e.getMessage());
            return "주문 내역 조회에 실패했습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            고객 본인의 회원 정보(이름, 이메일, 전화번호)를 조회합니다.
            언제 호출: 회원 정보 확인/변경 관련 문의에 호출.
            """)
    public String getMyProfile() {
        try {
            UserProfileResponse profile = csBotClient.getMyProfile(csUserContext.resolveNumericId(), csUserContext.getLoginId());
            return "이름: %s, 이메일: %s, 전화번호: %s".formatted(profile.username(), profile.email(), profile.phoneNumber());
        } catch (Exception e) {
            log.warn("[CsBotTools] 회원 정보 조회 실패: {}", e.getMessage());
            return "회원 정보 조회에 실패했습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            본인의 주문에 대해 환불/취소를 요청합니다.
            언제 호출: getMyOrders로 본인 주문을 먼저 확인한 뒤, 고객이 특정 주문번호의 환불/취소를 요청할 때.
            처리: 결제 상태가 PAID인 본인 주문만 취소 요청을 접수한다. 처리는 비동기이며, 결과는 잠시 후 getMyOrders로 재확인할 수 있다.
            """)
    public String requestRefund(@ToolParam(description = "취소할 주문번호 (orderId)") String orderId) {
        try {
            List<PaymentResponse> payments = csBotClient.getMyPayments(csUserContext.resolveNumericId());
            PaymentResponse target = payments.stream()
                    .filter(p -> p.orderId().equals(orderId))
                    .findFirst()
                    .orElse(null);

            if (target == null) {
                return "본인의 주문 중 주문번호 " + orderId + "를 찾을 수 없습니다.";
            }
            if (!CANCELABLE_STATUS.equals(target.status())) {
                return "주문번호 " + orderId + "는 현재 상태(" + target.status() + ")에서는 취소할 수 없습니다.";
            }

            String payload = objectMapper.writeValueAsString(new PaymentCancelMessage(orderId));
            kafkaTemplate.send(PAYMENT_CANCEL_TOPIC, orderId, payload);

            log.info("[CsBotTools] 환불/취소 요청 접수: orderId={}", orderId);
            return "주문번호 " + orderId + "의 취소 요청이 접수되었습니다. 처리 결과는 잠시 후 다시 조회해 주세요.";
        } catch (Exception e) {
            log.warn("[CsBotTools] 환불/취소 요청 실패: orderId={}, error={}", orderId, e.getMessage());
            return "환불/취소 요청 처리 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            특정 주문의 상세 정보를 조회합니다: 상품명, 결제 정보, 주문 처리 상태.
            언제 호출: 고객이 특정 주문번호를 언급하며 상세 내역을 물어볼 때.
            """)
    public String getOrderDetail(@ToolParam(description = "조회할 주문번호 (orderId)") String orderId) {
        try {
            List<PaymentResponse> payments = csBotClient.getMyPayments(csUserContext.resolveNumericId());
            PaymentResponse target = payments.stream()
                    .filter(p -> p.orderId().equals(orderId))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return "본인의 주문 중 주문번호 " + orderId + "를 찾을 수 없습니다.";
            }

            String goodsName = "상품ID " + target.goodsId();
            try {
                GoodsResponse goods = csBotClient.getGoodsInfo(target.goodsId());
                goodsName = goods.name();
            } catch (Exception ignored) {}

            String orderStatus = "확인 불가";
            try {
                OrderStatusResponse statusResp = csBotClient.getOrderStatus(orderId);
                orderStatus = statusResp.status();
            } catch (Exception ignored) {}

            return "주문번호: %s\n상품명: %s\n수량: %d\n결제수단: %s\n결제 상태: %s\n주문 처리 상태: %s\n주문일시: %s"
                    .formatted(orderId, goodsName, target.quantity(), target.paymentMethod(),
                            target.status(), orderStatus, target.createdAt());
        } catch (Exception e) {
            log.warn("[CsBotTools] 주문 상세 조회 실패: orderId={}, error={}", orderId, e.getMessage());
            return "주문 상세 조회에 실패했습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            환불/취소 처리 상태를 추적합니다.
            언제 호출: requestRefund로 취소 요청 후 실제 처리됐는지 확인할 때. "환불이 됐나요", "취소 확인" 문의 시.
            반환: CANCELLED=완료, PAID=아직 처리 중(requestRefund 재시도 가능).
            """)
    public String trackRefundStatus(@ToolParam(description = "확인할 주문번호 (orderId)") String orderId) {
        try {
            List<PaymentResponse> payments = csBotClient.getMyPayments(csUserContext.resolveNumericId());
            PaymentResponse target = payments.stream()
                    .filter(p -> p.orderId().equals(orderId))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return "본인의 주문 중 주문번호 " + orderId + "를 찾을 수 없습니다.";
            }
            return switch (target.status()) {
                case "CANCELLED" -> "주문번호 " + orderId + "의 환불이 완료되었습니다.";
                case "PAID" -> "주문번호 " + orderId + "의 취소 처리가 아직 진행 중입니다. 잠시 후 다시 확인하거나, 지연되면 requestRefund를 다시 시도할 수 있습니다.";
                default -> "주문번호 " + orderId + "의 현재 상태는 " + target.status() + "입니다.";
            };
        } catch (Exception e) {
            log.warn("[CsBotTools] 환불 상태 조회 실패: orderId={}, error={}", orderId, e.getMessage());
            return "환불 상태 조회에 실패했습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            결제 이상 징후를 진단합니다: 결제 완료됐지만 주문 미처리, 동일 상품 10분 내 중복 결제.
            언제 호출: 고객이 "뭔가 이상해", "중복 결제된 것 같아" 등을 언급할 때.
            """)
    public String diagnosePaymentIssue() {
        try {
            List<PaymentResponse> payments = csBotClient.getMyPayments(csUserContext.resolveNumericId());
            if (payments.isEmpty()) {
                return "조회된 주문 내역이 없어 진단할 내용이 없습니다.";
            }

            List<String> issues = new ArrayList<>();

            for (PaymentResponse p : payments) {
                if ("PAID".equals(p.status())) {
                    try {
                        OrderStatusResponse orderStatus = csBotClient.getOrderStatus(p.orderId());
                        if ("NOT_FOUND".equals(orderStatus.status())) {
                            issues.add("주문번호 " + p.orderId() + ": 결제 완료됐지만 주문 처리가 확인되지 않습니다. checkAndRetryPurchaseDlt로 재처리를 시도해 보세요.");
                        }
                    } catch (Exception ignored) {}
                }
            }

            Map<Long, List<PaymentResponse>> byGoods = payments.stream()
                    .collect(Collectors.groupingBy(PaymentResponse::goodsId));
            for (Map.Entry<Long, List<PaymentResponse>> entry : byGoods.entrySet()) {
                List<PaymentResponse> sorted = entry.getValue().stream()
                        .sorted(Comparator.comparing(PaymentResponse::createdAt))
                        .toList();
                for (int i = 0; i < sorted.size() - 1; i++) {
                    long minutes = Duration.between(sorted.get(i).createdAt(), sorted.get(i + 1).createdAt()).toMinutes();
                    if (minutes < 10) {
                        issues.add("상품ID " + entry.getKey() + "에 대해 " + minutes + "분 간격 중복 결제 감지: "
                                + sorted.get(i).orderId() + ", " + sorted.get(i + 1).orderId()
                                + " — 중복이라면 requestRefund로 하나를 취소할 수 있습니다.");
                    }
                }
            }

            if (issues.isEmpty()) {
                return "이상 징후가 감지되지 않았습니다. 결제와 주문이 정상 처리되어 있습니다.";
            }
            return "감지된 이상 징후:\n" + String.join("\n", issues);
        } catch (Exception e) {
            log.warn("[CsBotTools] 결제 진단 실패: {}", e.getMessage());
            return "진단 중 오류가 발생했습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            결제는 됐는데 주문 처리가 안 된 것 같을 때, 구매 실패 기록(DLT)을 조회하고 미해결이면 재처리(재고 복구)를 수행합니다.
            언제 호출: "결제됐는데 주문이 안 됐어", "결제 후 주문 처리 실패" 등의 문의 시. getMyOrders로 orderId 확인 후 호출.
            """)
    public String checkAndRetryPurchaseDlt(@ToolParam(description = "확인할 주문번호 (orderId)") String orderId) {
        try {
            List<PaymentResponse> payments = csBotClient.getMyPayments(csUserContext.resolveNumericId());
            boolean isOwner = payments.stream().anyMatch(p -> p.orderId().equals(orderId));
            if (!isOwner) {
                return "본인의 주문 중 주문번호 " + orderId + "를 찾을 수 없습니다.";
            }

            DltResponse dlt;
            try {
                dlt = csBotClient.getDltByOrderId(orderId);
            } catch (Exception e) {
                return "주문번호 " + orderId + "의 구매 처리 기록은 정상입니다.";
            }

            if ("RESOLVED".equals(dlt.status())) {
                return "주문번호 " + orderId + "의 구매 오류는 이미 처리 완료되었습니다.";
            }

            csBotClient.retryDlt(dlt.id());
            log.info("[CsBotTools] DLT 재처리 완료: orderId={}, dltId={}", orderId, dlt.id());
            return "주문번호 " + orderId + "의 처리 오류가 감지되어 재처리를 완료했습니다. 재고가 복구되었으니 다시 구매를 시도해 주세요.";
        } catch (Exception e) {
            log.warn("[CsBotTools] DLT 재처리 실패: orderId={}, error={}", orderId, e.getMessage());
            return "구매 오류 재처리 중 문제가 발생했습니다: " + e.getMessage();
        }
    }

    @Tool(description = """
            챗봇이 해결할 수 없는 문의를 상담원에게 에스컬레이션합니다(Linear 이슈 생성).
            언제 호출: 위 도구들로 해결되지 않거나, 고객이 명시적으로 상담원 연결을 요청할 때.
            """)
    public String escalateToHuman(
            @ToolParam(description = """
                    상담원이 고객에게 다시 묻지 않고 바로 이어서 처리할 수 있도록 작성하는 요약.
                    반드시 포함: (1) 고객이 겪고 있는 문제, (2) 이번 대화에서 이미 호출한 도구와 그 결과
                    (예: getMyOrders 조회 결과, checkAndRetryPurchaseDlt 재처리 결과, diagnosePaymentIssue 진단 결과 등).
                    """) String summary) {
        return escalationService.createEscalationTicket(
                summary, csUserContext.getLoginId(), csUserContext.getUrgency(),
                csUserContext.getConversationId(), csUserContext.getOriginalMessage());
    }
}
