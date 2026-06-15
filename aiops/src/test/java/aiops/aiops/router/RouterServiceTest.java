package aiops.aiops.router;

import aiops.aiops.router.IntentClassifierService.RouteIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("RouterService 단위 테스트")
class RouterServiceTest {

    @Mock
    private IntentClassifierService intentClassifierService;

    @Mock
    private InfraChatAgentService infraChatAgentService;

    @Mock
    private CodebotClient codebotClient;

    @Mock
    private SlackBotClient slackBotClient;

    private RouterService routerService;

    @BeforeEach
    void setUp() {
        routerService = new RouterService(intentClassifierService, infraChatAgentService, codebotClient, slackBotClient);
    }

    @Test
    @DisplayName("INFRA로 분류되면 InfraChatAgentService 응답을 같은 스레드에 게시한다")
    void handleAppMention_INFRA() {
        // given
        given(intentClassifierService.classify("파드 상태 확인해줘")).willReturn(RouteIntent.INFRA);
        given(infraChatAgentService.chat("thread-1", "파드 상태 확인해줘")).willReturn("파드 상태는 정상입니다.");

        // when
        routerService.handleAppMention("C123", "thread-1", "파드 상태 확인해줘");

        // then
        then(slackBotClient).should().postMessage("C123", "thread-1", "파드 상태는 정상입니다.");
    }

    @Test
    @DisplayName("CODE로 분류되면 CodebotClient 조사 결과를 같은 스레드에 게시한다")
    void handleAppMention_CODE() {
        // given
        given(intentClassifierService.classify("NPE 발생")).willReturn(RouteIntent.CODE);
        given(codebotClient.investigate("thread-1", "NPE 발생")).willReturn("조사 결과: ...");

        // when
        routerService.handleAppMention("C123", "thread-1", "NPE 발생");

        // then
        then(slackBotClient).should().postMessage("C123", "thread-1", "조사 결과: ...");
    }

    @Test
    @DisplayName("UNKNOWN으로 분류되면 고정 안내 문구를 게시한다")
    void handleAppMention_UNKNOWN() {
        // given
        given(intentClassifierService.classify("안녕")).willReturn(RouteIntent.UNKNOWN);

        // when
        routerService.handleAppMention("C123", "thread-1", "안녕");

        // then
        then(slackBotClient).should().postMessage("C123", "thread-1", "인프라 문제인지 코드/기능 문제인지 알려주세요.");
    }

    @Test
    @DisplayName("CODE로 캐시된 스레드에서 다음 메시지가 UNKNOWN으로 분류되면 CodebotClient로 라우팅한다")
    void handleAppMention_UNKNOWN_캐시된CODE로라우팅() {
        // given
        given(intentClassifierService.classify("NPE 발생")).willReturn(RouteIntent.CODE);
        given(codebotClient.investigate("thread-1", "NPE 발생")).willReturn("조사 결과: ...");
        given(intentClassifierService.classify("진행해")).willReturn(RouteIntent.UNKNOWN);
        given(codebotClient.investigate("thread-1", "진행해")).willReturn("이슈를 생성했습니다.");

        // when
        routerService.handleAppMention("C123", "thread-1", "NPE 발생");
        routerService.handleAppMention("C123", "thread-1", "진행해");

        // then
        then(slackBotClient).should().postMessage("C123", "thread-1", "이슈를 생성했습니다.");
        then(slackBotClient).should(never()).postMessage("C123", "thread-1", "인프라 문제인지 코드/기능 문제인지 알려주세요.");
    }

    @Test
    @DisplayName("같은 스레드에서 INFRA 다음 CODE로 분류되면 캐시가 최신 라우트로 갱신된다")
    void handleAppMention_캐시는최신분류로갱신된다() {
        // given
        given(intentClassifierService.classify("파드 상태 확인해줘")).willReturn(RouteIntent.INFRA);
        given(infraChatAgentService.chat("thread-1", "파드 상태 확인해줘")).willReturn("파드 상태는 정상입니다.");
        given(intentClassifierService.classify("NPE 발생")).willReturn(RouteIntent.CODE);
        given(codebotClient.investigate("thread-1", "NPE 발생")).willReturn("조사 결과: ...");
        given(intentClassifierService.classify("진행해")).willReturn(RouteIntent.UNKNOWN);
        given(codebotClient.investigate("thread-1", "진행해")).willReturn("이슈를 생성했습니다.");

        // when
        routerService.handleAppMention("C123", "thread-1", "파드 상태 확인해줘");
        routerService.handleAppMention("C123", "thread-1", "NPE 발생");
        routerService.handleAppMention("C123", "thread-1", "진행해");

        // then
        then(slackBotClient).should().postMessage("C123", "thread-1", "이슈를 생성했습니다.");
        then(infraChatAgentService).should(never()).chat("thread-1", "진행해");
    }
}
