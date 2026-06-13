package aiops.aiops.router;

import aiops.aiops.router.IntentClassifierService.RouteIntent;
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

    public void handleAppMention(String channel, String threadTs, String text) {
        RouteIntent intent = intentClassifierService.classify(text);
        log.info("[Router] 의도 분류: {}", intent);

        String response = switch (intent) {
            case INFRA -> infraChatAgentService.chat(threadTs, text);
            case CODE -> codebotClient.investigate(threadTs, text);
            case UNKNOWN -> UNKNOWN_GUIDANCE;
        };

        slackBotClient.postMessage(channel, threadTs, response);
    }
}
