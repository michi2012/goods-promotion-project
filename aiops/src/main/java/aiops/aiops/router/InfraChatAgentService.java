package aiops.aiops.router;

import aiops.aiops.tools.KubernetesTools;
import aiops.aiops.tools.ObservabilityTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfraChatAgentService {

    private static final String SYSTEM_PROMPT = """
            당신은 인프라 운영을 지원하는 챗봇입니다. Slack 스레드에서 개발자와 여러 차례 대화하며 인프라 상태를 조사하고 안내합니다.

            ## 조사 원칙
            - 질문과 관련된 메트릭(queryPrometheusMetrics), 로그(queryLokiLogs), 트레이스(queryTempoTrace), 클러스터/메시 상태(getClusterStatus, getIstioMeshStatus) 등 필요한 도구만 선택해 호출한다.
            - 확정되지 않은 원인은 반드시 "가설:" 로 표기한다. 단정하지 않는다.
            - 이전 대화 맥락을 참고해 중복 질문을 피하고, 조사에 필요한 정보가 부족하면 사용자에게 먼저 질문한다.

            ## 조치 제안
            - propose*(롤아웃 재시작, HPA 패치, Helm 롤백, 트래픽 전환 등) 도구는 조사로 뒷받침되는 근거가 있을 때만 호출한다. 추측만으로 제안하지 않는다.
            - 호출 시 Slack에 [승인]/[거절] 버튼이 포함된 별도 메시지가 발송됨을 사용자에게 안내한다.

            ## 응답 형식
            - 한국어로, Slack 스레드에 그대로 게시될 자연스러운 문장으로 답한다.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final ObservabilityTools observabilityTools;
    private final KubernetesTools kubernetesTools;

    public String chat(String conversationId, String message) {
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(message)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .tools(observabilityTools, kubernetesTools)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[InfraChatAgent] 처리 실패: {}", e.getMessage());
            return "요청 처리 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
