package csbot.csbot.router;

import csbot.csbot.classification.CsClassification;
import csbot.csbot.classification.CsClassificationService;
import csbot.csbot.context.CsUserContext;
import csbot.csbot.tools.CsBotTools;
import csbot.csbot.tools.FaqSearchTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CsChatAgentService {

    private static final String SYSTEM_PROMPT = """
            당신은 고객 서비스(CS) 챗봇입니다. 로그인한 고객과 1:1로 대화하며 구매/결제/회원 정보 관련 문의를 처리합니다.

            ## 핵심 원칙
            - 반드시 본인의 데이터만 조회·처리합니다. 다른 고객의 정보를 절대 조회하거나 언급하지 않습니다.
            - 주문·결제 관련 문의가 들어오면 반드시 getMyOrders를 먼저 호출해 실제 데이터를 확인한 후 답변합니다.
            - 환불/취소 요청 시 getMyOrders로 본인 주문을 확인한 후 requestRefund를 호출합니다.
            - 위 도구로 해결할 수 없거나 고객이 상담원 연결을 요청하면 escalateToHuman을 호출합니다.
            - 환불 기간, 배송비, 구매 제한 등 정책성 질문(본인 데이터 조회가 아닌 일반 규정 문의)에는 searchFaq를 호출해 답변합니다.
              searchFaq 결과로도 해결되지 않으면 escalateToHuman을 호출합니다.

            ## 시나리오별 처리 지침
            - "취소 요청했는데 환불이 안 됐어": trackRefundStatus → PAID이면 requestRefund를 다시 시도하도록 안내합니다.
            - "결제됐는데 주문이 안 됐어": checkAndRetryPurchaseDlt를 호출해 자동 재처리를 시도합니다.
            - "중복 결제된 것 같아" / "뭔가 이상해": diagnosePaymentIssue를 호출해 이상 징후를 진단합니다.
            - 특정 주문 상세가 필요하면: getOrderDetail을 호출합니다.
            - "결제 실패했어" / FAILED 상태 주문: checkAndRetryPurchaseDlt로 DLT 재처리를 먼저 시도하고, 없으면 escalateToHuman으로 넘깁니다.

            ## 응답 형식
            - 친절하고 명확한 한국어로 답변합니다.
            - 처리 결과(성공/실패)를 고객에게 명확히 안내합니다.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final CsBotTools csBotTools;
    private final FaqSearchTools faqSearchTools;
    private final CsClassificationService classificationService;
    private final CsUserContext csUserContext;

    public String chat(String conversationId, String message) {
        try {
            CsClassification classification = classificationService.classify(message);
            csUserContext.setUrgency(classification.urgency());
            csUserContext.setConversationId(conversationId);
            csUserContext.setOriginalMessage(message);

            return chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(message)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .tools(csBotTools, faqSearchTools)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[CsChatAgent] 처리 실패: {}", e.getMessage());
            return "요청 처리 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
