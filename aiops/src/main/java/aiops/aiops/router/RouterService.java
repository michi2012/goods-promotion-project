package aiops.aiops.router;

import aiops.aiops.router.IntentClassifierService.RouteIntent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouterService {

    private static final String UNKNOWN_GUIDANCE = "인프라 문제인지 코드/기능 문제인지 알려주세요.";

    private final IntentClassifierService intentClassifierService;
    private final InfraChatAgentService infraChatAgentService;
    private final CodebotClient codebotClient;
    private final SlackBotClient slackBotClient;

    private final Map<String, RouteIntent> threadRouteCache = new ConcurrentHashMap<>();

    public void handleAppMention(String channel, String threadTs, String text) {
        RouteIntent intent = intentClassifierService.classify(text);
        log.info("[Router] 의도 분류: {}", intent);

        if (intent == RouteIntent.UNKNOWN) {
            RouteIntent cachedIntent = threadRouteCache.get(threadTs);
            if (cachedIntent != null) {
                log.info("[Router] UNKNOWN 분류, 스레드의 직전 라우트로 재사용: {}", cachedIntent);
                intent = cachedIntent;
            }
        } else {
            threadRouteCache.put(threadTs, intent);
        }

        String response = switch (intent) {
            case INFRA -> infraChatAgentService.chat(threadTs, text);
            case CODE -> codebotClient.investigate(threadTs, text);
            case UNKNOWN -> UNKNOWN_GUIDANCE;
        };

        slackBotClient.postMessage(channel, threadTs, response);
    }
}
