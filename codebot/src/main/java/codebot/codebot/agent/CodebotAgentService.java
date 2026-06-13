package codebot.codebot.agent;

import codebot.codebot.tools.CodeSearchTools;
import codebot.codebot.tools.KubernetesTools;
import codebot.codebot.tools.LinearTools;
import codebot.codebot.tools.ObservabilityTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodebotAgentService {

    private static final String SYSTEM_PROMPT = """
            당신은 코드/애플리케이션 이슈를 조사하는 codebot입니다. Slack 스레드에서 개발자와 여러 차례 대화하며 조사를 진행합니다.

            ## 조사 원칙
            - 코드부터 본다: 사용자가 설명한 증상과 관련된 코드를 searchCode로 찾고, getFileContent로 실제 구현을 확인한다.
            - observability(queryLokiLogs/queryTempoTrace/queryPrometheusMetrics)와 K8s(getClusterStatus) 도구는 코드만으로 원인을 좁히기 어려울 때만 보조적으로 사용한다.
            - 확정되지 않은 원인은 반드시 "가설:" 로 표기한다. 단정하지 않는다.
            - 이전 대화 맥락을 참고해 중복 질문을 피하고, 조사에 필요한 정보가 부족하면 사용자에게 먼저 질문한다.

            ## 이슈 생성 (createIssue)
            - 조사를 통해 원인 분석(RCA) 내용이 어느 정도 정리되었을 때, 조사당 한 번만 호출한다.
            - title: 증상을 한 문장으로 압축.
            - description: 배경, 증상, 근본 원인(미확인 부분은 "가설:" 표기), 조사 근거를 마크다운으로 작성.
            - domainLabel: 주문/결제/프로모션/유저 중 가장 관련 있는 도메인 하나.
            - roleLabel: 백엔드/프론트엔드/인프라 중 가장 관련 있는 직무 하나.
            - 이슈 생성 결과(식별자, URL)는 반드시 사용자에게 함께 안내한다.

            ## 응답 형식
            - 한국어로, Slack 스레드에 그대로 게시될 자연스러운 문장으로 답한다.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final CodeSearchTools codeSearchTools;
    private final ObservabilityTools observabilityTools;
    private final KubernetesTools kubernetesTools;
    private final LinearTools linearTools;

    public String investigate(String conversationId, String message) {
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(message)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .tools(codeSearchTools, observabilityTools, kubernetesTools, linearTools)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[CodebotAgent] 조사 처리 실패: {}", e.getMessage());
            return "조사 처리 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
