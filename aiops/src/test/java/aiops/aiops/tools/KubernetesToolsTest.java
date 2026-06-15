package aiops.aiops.tools;

import aiops.aiops.approval.ActionApprovalService;
import aiops.aiops.slack.SlackNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("KubernetesTools 단위 테스트")
class KubernetesToolsTest {

    @Mock
    private ActionApprovalService approvalService;

    @Mock
    private SlackNotificationService slackService;

    private KubernetesTools kubernetesTools;

    @BeforeEach
    void setUp() {
        kubernetesTools = new KubernetesTools(approvalService, slackService, "promotion");
    }

    @Test
    @DisplayName("BLOCKED 상태인 스레드 블록만 추출한다")
    void extractBlockedThreads_BLOCKED스레드만추출() {
        // given
        String dump = """
                "http-nio-8080-exec-1" #23 daemon prio=5 os_prio=0 cpu=12.34ms elapsed=120.45s tid=0x1 nid=0x1a waiting for monitor entry  [0x1]
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at com.example.serverA.service.OrderService.processOrder(OrderService.java:42)
                	- waiting to lock <0x1> (a java.lang.Object)

                "http-nio-8080-exec-2" #24 daemon prio=5 os_prio=0 cpu=5.00ms elapsed=120.45s tid=0x2 nid=0x1b runnable [0x2]
                   java.lang.Thread.State: RUNNABLE
                	at java.net.SocketInputStream.socketRead0(java.base@21/Native Method)

                "pool-2-thread-1" #25 prio=5 os_prio=0 cpu=2.00ms elapsed=119.00s tid=0x3 nid=0x1c waiting for monitor entry  [0x3]
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at com.example.serverA.service.OrderService.releaseLock(OrderService.java:55)
                	- waiting to lock <0x2> (a java.lang.Object)
                """;

        // when
        String result = kubernetesTools.extractBlockedThreads(dump);

        // then
        assertThat(result)
                .contains("http-nio-8080-exec-1")
                .contains("pool-2-thread-1")
                .doesNotContain("http-nio-8080-exec-2");
    }

    @Test
    @DisplayName("BLOCKED 상태인 스레드가 없으면 안내 문구를 반환한다")
    void extractBlockedThreads_BLOCKED스레드없음() {
        // given
        String dump = """
                "http-nio-8080-exec-1" #23 daemon prio=5 os_prio=0 cpu=12.34ms elapsed=120.45s tid=0x1 nid=0x1a runnable [0x1]
                   java.lang.Thread.State: RUNNABLE
                	at java.net.SocketInputStream.socketRead0(java.base@21/Native Method)
                """;

        // when
        String result = kubernetesTools.extractBlockedThreads(dump);

        // then
        assertThat(result).isEqualTo("BLOCKED 상태 스레드 없음");
    }

    @Test
    @DisplayName("결과가 2500자를 초과하면 잘라내고 생략 표시를 추가한다")
    void extractBlockedThreads_길이초과시잘라냄() {
        // given
        StringBuilder dump = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            dump.append("\"thread-").append(i).append("\" #1 prio=5 os_prio=0 tid=0x1 nid=0x1 waiting for monitor entry  [0x1]\n");
            dump.append("   java.lang.Thread.State: BLOCKED (on object monitor)\n");
            dump.append("\tat com.example.serverA.service.OrderService.method").append(i).append("(OrderService.java:").append(i).append(")\n");
            dump.append("\t- waiting to lock <0x1> (a java.lang.Object)\n\n");
        }

        // when
        String result = kubernetesTools.extractBlockedThreads(dump.toString());

        // then
        assertThat(result).endsWith("... (이하 생략)");
        assertThat(result.length()).isEqualTo(2500 + "\n... (이하 생략)".length());
    }

    @Test
    @DisplayName("parseCanaryWeight: 정상 숫자 문자열은 정수로 변환한다")
    void parseCanaryWeight_정상값() {
        assertThat(kubernetesTools.parseCanaryWeight("25")).isEqualTo(25);
        assertThat(kubernetesTools.parseCanaryWeight(" 100 ")).isEqualTo(100);
    }

    @Test
    @DisplayName("parseCanaryWeight: 빈 값/비숫자 문자열은 -1을 반환한다")
    void parseCanaryWeight_파싱실패시_minus1() {
        assertThat(kubernetesTools.parseCanaryWeight("")).isEqualTo(-1);
        assertThat(kubernetesTools.parseCanaryWeight("(결과 없음)")).isEqualTo(-1);
    }

    @Test
    @DisplayName("getCanaryWeight: kubectl 조회 실패 시 -1을 반환한다")
    void getCanaryWeight_kubectl실패시_minus1() {
        // when: 존재하지 않는 VirtualService 또는 kubectl 접근 불가 환경
        int result = kubernetesTools.getCanaryWeight("nonexistent-service");

        // then
        assertThat(result).isEqualTo(-1);
    }

    @Test
    @DisplayName("getPodLogs: kubectl 접근 불가 시 실패 안내 문자열을 반환한다")
    void getPodLogs_kubectl실패시_안내문자열반환() {
        // when: kubectl 접근 불가 환경
        String result = kubernetesTools.getPodLogs("nonexistent-pod", 100);

        // then
        assertThat(result).startsWith("Pod 로그 조회 실패");
    }

    @Test
    @DisplayName("getRolloutStatus: kubectl 접근 불가 시 실패 안내 문자열을 반환한다")
    void getRolloutStatus_kubectl실패시_안내문자열반환() {
        // when: kubectl 접근 불가 환경
        String result = kubernetesTools.getRolloutStatus("nonexistent-deployment");

        // then
        assertThat(result).startsWith("롤아웃 상태 조회 실패");
    }

    @Test
    @DisplayName("getRolloutHistory: kubectl 접근 불가 시 실패 안내 문자열을 반환한다")
    void getRolloutHistory_kubectl실패시_안내문자열반환() {
        // when: kubectl 접근 불가 환경
        String result = kubernetesTools.getRolloutHistory("nonexistent-deployment");

        // then
        assertThat(result).startsWith("롤아웃 이력 조회 실패");
    }
}
