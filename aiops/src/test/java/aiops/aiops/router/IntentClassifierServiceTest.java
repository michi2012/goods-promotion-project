package aiops.aiops.router;

import aiops.aiops.router.IntentClassifierService.RouteDecision;
import aiops.aiops.router.IntentClassifierService.RouteIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("IntentClassifierService 단위 테스트")
class IntentClassifierServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private IntentClassifierService classifierService;

    @BeforeEach
    void setUp() {
        given(chatClientBuilder.build()).willReturn(chatClient);
        given(chatClient.prompt()).willReturn(requestSpec);
        given(requestSpec.system(anyString())).willReturn(requestSpec);
        given(requestSpec.user(anyString())).willReturn(requestSpec);
        given(requestSpec.call()).willReturn(callResponseSpec);

        classifierService = new IntentClassifierService(chatClientBuilder);
    }

    @Test
    @DisplayName("인프라 관련 메시지는 INFRA로 분류한다")
    void classify_INFRA로분류() {
        // given
        given(callResponseSpec.entity(RouteDecision.class)).willReturn(new RouteDecision(RouteIntent.INFRA));

        // when
        RouteIntent result = classifierService.classify("파드가 자꾸 재시작돼요");

        // then
        assertThat(result).isEqualTo(RouteIntent.INFRA);
    }

    @Test
    @DisplayName("코드 관련 메시지는 CODE로 분류한다")
    void classify_CODE로분류() {
        // given
        given(callResponseSpec.entity(RouteDecision.class)).willReturn(new RouteDecision(RouteIntent.CODE));

        // when
        RouteIntent result = classifierService.classify("PaymentService에서 NPE가 발생해요");

        // then
        assertThat(result).isEqualTo(RouteIntent.CODE);
    }

    @Test
    @DisplayName("분류가 모호한 메시지는 UNKNOWN으로 분류한다")
    void classify_UNKNOWN으로분류() {
        // given
        given(callResponseSpec.entity(RouteDecision.class)).willReturn(new RouteDecision(RouteIntent.UNKNOWN));

        // when
        RouteIntent result = classifierService.classify("안녕하세요");

        // then
        assertThat(result).isEqualTo(RouteIntent.UNKNOWN);
    }

    @Test
    @DisplayName("분류 중 예외가 발생하면 UNKNOWN으로 처리한다")
    void classify_예외발생시UNKNOWN으로처리() {
        // given
        given(callResponseSpec.entity(RouteDecision.class)).willThrow(new RuntimeException("LLM 호출 실패"));

        // when
        RouteIntent result = classifierService.classify("메시지");

        // then
        assertThat(result).isEqualTo(RouteIntent.UNKNOWN);
    }
}
