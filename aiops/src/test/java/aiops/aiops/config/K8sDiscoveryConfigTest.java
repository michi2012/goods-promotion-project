package aiops.aiops.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClient;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration;
import org.springframework.cloud.commons.util.UtilAutoConfiguration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// K8s에는 Eureka가 없으므로 CodebotClient가 codebot을 찾으려면 application-k8s.yml의
// SimpleDiscoveryClient 정적 등록이 필요하다. 키 오타 시 조용히 조회 실패하므로 바인딩을 검증한다.
@DisplayName("aiops application-k8s.yml SimpleDiscoveryClient 정적 등록")
class K8sDiscoveryConfigTest {

    @Test
    @DisplayName("k8s 프로필 설정으로 codebot 인스턴스가 K8s Service DNS로 조회된다")
    void codebotResolvedToK8sServiceDns() {
        new ApplicationContextRunner()
                .withInitializer(ctx -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load("application-k8s", new ClassPathResource("application-k8s.yml"))
                                .forEach(ctx.getEnvironment().getPropertySources()::addFirst);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .withConfiguration(AutoConfigurations.of(
                        UtilAutoConfiguration.class, SimpleDiscoveryClientAutoConfiguration.class))
                .run(ctx -> {
                    List<ServiceInstance> instances = ctx.getBean(SimpleDiscoveryClient.class).getInstances("codebot");

                    assertThat(instances).hasSize(1);
                    assertThat(instances.get(0).getUri().toString()).isEqualTo("http://codebot:8087");
                });
    }
}
