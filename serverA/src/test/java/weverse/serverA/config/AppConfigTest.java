package weverse.serverA.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AppConfigTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("서버 A의 커스텀 설정들이 환경에 맞게 정상적으로 컨텍스트에 로드/제외된다.")
    void contextLoadsAndBeansAreRegistered() {
        // 1. RestTemplateConfig 검증 (항상 있어야 함)
        boolean hasRestTemplate = context.containsBean("restTemplate");
        assertThat(hasRestTemplate).isTrue();

        RestTemplate restTemplate = context.getBean(RestTemplate.class);
        assertThat(restTemplate).isNotNull();

        // 2. AppConfig 검증 (항상 있어야 함)
        assertThat(context.containsBean("appConfig")).isTrue();

        // 💡 3. SchedulingConfig 검증 (테스트 환경에서는 꺼져 있어야 정상!)
        assertThat(context.containsBean("schedulingConfig")).isFalse(); // 👈 isTrue()에서 isFalse()로 변경!
    }
}