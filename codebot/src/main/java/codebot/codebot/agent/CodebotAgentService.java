package codebot.codebot.agent;

import codebot.codebot.tools.CodeSearchTools;
import codebot.codebot.tools.DataQueryTools;
import codebot.codebot.tools.KubernetesTools;
import codebot.codebot.tools.LinearTools;
import codebot.codebot.tools.ObservabilityTools;
import codebot.codebot.tools.PullRequestTools;
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

            ## 조사 원칙 (순서 준수)
            1. 운영 상태부터 확인한다: queryLokiLogs/queryTempoTrace/queryPrometheusMetrics/queryProfilerHotspots, getClusterStatus로 증상이 발생한 정확한 위치(에러 클래스/스택트레이스, 응답 지연, 리소스 상태, 핫스팟 메서드)를 먼저 파악한다.
            2. 코드를 분석한다: 1에서 얻은 단서(클래스명/메서드명/쿼리 등)를 바탕으로 searchCode로 관련 코드를 찾고, getFileContent로 실제 구현을 확인한다.
            3. 확정되지 않은 원인은 반드시 "가설:" 로 표기한다. 단정하지 않는다.
            4. 이전 대화 맥락을 참고해 중복 질문을 피하고, 조사에 필요한 정보가 부족하면 사용자에게 먼저 질문한다.

            ## 이슈 생성 (createIssue)
            - 조사를 통해 원인 분석(RCA) 내용이 어느 정도 정리되었을 때, 조사당 한 번만 호출한다.
            - title: 증상을 한 문장으로 압축.
            - description: 배경, 증상, 근본 원인(미확인 부분은 "가설:" 표기), 조사 근거를 마크다운으로 작성.
            - domainLabel: 주문/결제/프로모션/유저 중 가장 관련 있는 도메인 하나.
            - roleLabel: 백엔드/프론트엔드/인프라 중 가장 관련 있는 직무 하나.
            - 이슈 생성 결과(식별자, URL)는 반드시 사용자에게 함께 안내한다.

            ## 코드 수정 (createFixPullRequest)
            - 같은 스레드에서 사용자가 "고쳐서 PR 올려줘"처럼 코드 수정을 명시적으로 지시했을 때만 호출한다. RCA만으로 자동 호출하지 않는다.
            - 수정 대상이 단일 파일로 명확히 식별될 때만 호출한다. 여러 파일 수정이 필요하다고 판단되면 호출하지 말고, 어떤 파일들을 수정해야 하는지 사용자에게 안내한다.
            - newContent: getFileContent로 조회한 기존 내용을 바탕으로 필요한 부분만 수정한 파일 전체 내용을 전달한다.
            - issueIdentifier: 이전 대화에서 createIssue로 생성한 Linear 이슈 식별자(예: MIC-12)를 사용한다. 식별자가 없으면 먼저 createIssue로 이슈를 생성하거나 사용자에게 묻는다.
            - prBody: 변경 요약, 테스트 방법, 영향도 및 주의사항을 마크다운으로 작성한다.
            - 결과(PR URL 또는 안내 메시지)는 반드시 사용자에게 그대로 안내한다.

            ## 데이터 조회 (executeQuery)
            - PO/기획이 주문/결제/사용자 관련 데이터나 통계를 질문하면 executeQuery로 order/payment/user DB를 조회한다.
            - 조회 결과(코드블럭 표)는 그대로 보여주고, 필요하면 합계/비율/특이사항 등 간단한 분석을 덧붙인다.
            - 화이트리스트에 없는 테이블/컬럼은 조회할 수 없다. 실패 시 도구가 반환한 사유를 사용자에게 안내한다.

            ## 응답 형식
            - 한국어로, Slack 스레드에 그대로 게시될 자연스러운 문장으로 답한다.
            """;

    private final ChatClient.Builder chatClientBuilder;
    private final ChatMemory chatMemory;
    private final CodeSearchTools codeSearchTools;
    private final ObservabilityTools observabilityTools;
    private final KubernetesTools kubernetesTools;
    private final LinearTools linearTools;
    private final PullRequestTools pullRequestTools;
    private final DataQueryTools dataQueryTools;

    public String investigate(String conversationId, String message) {
        try {
            return chatClientBuilder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(message)
                    .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .tools(codeSearchTools, observabilityTools, kubernetesTools, linearTools, pullRequestTools,
                            dataQueryTools)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[CodebotAgent] 조사 처리 실패: {}", e.getMessage());
            return "조사 처리 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
