package com.example.gateway_service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게이트웨이는 라우트를 선언 순서대로 매칭한다. 환불 요청(POST /api/v1/orders/{id}/refund)은 serverA로 가야 하므로
 * serverB 주문 조회(/api/v1/orders/**)보다 앞에 있어야 한다. k8s 설정은 로컬에서 컨텍스트를 띄우기 어려워
 * 두 설정 파일을 직접 읽어 확인한다.
 */
class GatewayRouteOrderTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "application.yml, lb://serverA",
            "application-k8s.yml, http://server-a:8080"
    })
    void refundRouteGoesToServerABeforeServerBOrders(String file, String serverAUri) {
        List<Map<String, Object>> routes = routes(file);
        List<String> ids = routes.stream().map(r -> (String) r.get("id")).toList();

        assertThat(ids).contains("serverA-refund", "serverB-orders");
        assertThat(ids.indexOf("serverA-refund"))
                .as("serverA-refund는 serverB-orders보다 먼저 매칭돼야 한다")
                .isLessThan(ids.indexOf("serverB-orders"));

        Map<String, Object> refund = routes.get(ids.indexOf("serverA-refund"));
        assertThat(refund.get("uri")).isEqualTo(serverAUri);
        assertThat(refund.get("predicates"))
                .asList()
                .containsExactlyInAnyOrder("Path=/api/v1/orders/*/refund", "Method=POST");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routes(String file) {
        try (InputStream in = getClass().getResourceAsStream("/" + file)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> gateway = (Map<String, Object>)
                    ((Map<String, Object>) ((Map<String, Object>) root.get("spring")).get("cloud")).get("gateway");
            return (List<Map<String, Object>>) gateway.get("routes");
        } catch (Exception e) {
            throw new IllegalStateException(file + " 읽기 실패", e);
        }
    }
}
