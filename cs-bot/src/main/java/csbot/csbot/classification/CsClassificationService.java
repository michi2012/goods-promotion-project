package csbot.csbot.classification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CsClassificationService {

    private static final String CLASSIFY_PROMPT = """
            CS 챗봇에 들어온 고객 문의를 분류하세요.
            - urgency: HIGH(결제 오류·환불 지연·중복 결제 등 금전적 피해 가능성), MEDIUM(일반 불만/오류 문의), LOW(단순 정보성 질문)
            - category: PAYMENT(결제/환불), ORDER(주문/배송), GENERAL(일반/정책)
            - requiresHuman: 챗봇 도구로 해결이 어려워 보이거나 고객이 명시적으로 상담원을 요청하면 true
            """;

    private final ChatClient.Builder chatClientBuilder;

    public CsClassification classify(String userMessage) {
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(CLASSIFY_PROMPT)
                    .user(userMessage)
                    .call()
                    .entity(CsClassification.class);
        } catch (Exception e) {
            log.warn("[CsClassification] 분류 실패, 기본값(MEDIUM/GENERAL)으로 처리: {}", e.getMessage());
            return new CsClassification(CsClassification.Urgency.MEDIUM, "GENERAL", false);
        }
    }
}
