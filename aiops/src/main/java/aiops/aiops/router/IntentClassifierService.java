package aiops.aiops.router;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifierService {

    private static final String CLASSIFY_PROMPT = """
            Slack에서 개발자가 봇을 멘션하며 보낸 메시지를 다음 세 가지 중 하나로 분류하세요.

            - INFRA: 서버/인프라/배포/Kubernetes/메트릭/로그/장애 등 운영 관점의 문제나 질문
            - CODE: 특정 코드/로직/API/기능의 버그나 동작 이상에 대한 조사 요청, 또는 주문/결제/사용자 데이터·통계 조회 요청
            - UNKNOWN: 위 둘 중 어디에도 명확히 속하지 않거나 일반적인 대화

            메시지 내용만으로 가장 가능성이 높은 하나를 선택하세요.
            """;

    public enum RouteIntent {
        INFRA, CODE, UNKNOWN
    }

    public record RouteDecision(RouteIntent intent) {
    }

    private final ChatClient.Builder chatClientBuilder;

    public RouteIntent classify(String message) {
        try {
            RouteDecision decision = chatClientBuilder.build()
                    .prompt()
                    .system(CLASSIFY_PROMPT)
                    .user(message)
                    .call()
                    .entity(RouteDecision.class);
            log.info("[Router] 의도 분류 결과: {}", decision.intent());
            return decision.intent();
        } catch (Exception e) {
            log.warn("[Router] 의도 분류 실패, UNKNOWN으로 처리: {}", e.getMessage());
            return RouteIntent.UNKNOWN;
        }
    }
}
