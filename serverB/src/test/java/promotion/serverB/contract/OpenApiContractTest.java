package promotion.serverB.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * serverB가 실제로 노출하는 OpenAPI 스펙(/v3/api-docs)이 계약 원본(contracts/openapi.yaml)과
 * 어긋나지 않는지 확인한다. 계약은 사람이 먼저 쓰는 쪽이 원본이므로, 여기서 실패하면
 * 계약을 고칠지 구현을 고칠지는 사람이 판단한다 (자동으로 어느 쪽이 맞다고 판정하지 않음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    private static final String CONTRACT_PATH = "../contracts/openapi.yaml";
    private static final String TARGET_PATH = "/api/v1/orders/{orderId}/status";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("GET /api/v1/orders/{orderId}/status 응답 스키마가 계약과 일치한다")
    void orderStatusEndpointMatchesContract() throws Exception {
        String liveJson = restTemplate.getForObject("http://localhost:" + port + "/v3/api-docs", String.class);
        Map<String, Object> live = new ObjectMapper().readValue(liveJson, Map.class);

        Map<String, Object> contract;
        try (FileInputStream in = new FileInputStream(CONTRACT_PATH)) {
            contract = new Yaml().load(in);
        }

        Map<String, Object> liveProperties = resolveStatusResponseProperties(live);
        Map<String, Object> contractProperties = resolveStatusResponseProperties(contract);

        assertThat(liveProperties.keySet())
                .as("OrderStatusResponse 필드가 계약과 일치해야 한다")
                .isEqualTo(contractProperties.keySet());

        assertThat(enumValues(liveProperties))
                .as("status enum 값이 계약과 일치해야 한다")
                .containsExactlyInAnyOrderElementsOf(enumValues(contractProperties));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveStatusResponseProperties(Map<String, Object> spec) {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(TARGET_PATH);
        Map<String, Object> get = (Map<String, Object>) pathItem.get("get");
        Map<String, Object> responses = (Map<String, Object>) get.get("responses");
        Map<String, Object> ok = (Map<String, Object>) responses.get("200");
        Map<String, Object> content = (Map<String, Object>) ok.get("content");
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        Map<String, Object> schema = (Map<String, Object>) json.get("schema");
        String ref = (String) schema.get("$ref");
        String schemaName = ref.substring(ref.lastIndexOf('/') + 1);

        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> target = (Map<String, Object>) schemas.get(schemaName);
        return (Map<String, Object>) target.get("properties");
    }

    @SuppressWarnings("unchecked")
    private List<String> enumValues(Map<String, Object> properties) {
        Map<String, Object> status = (Map<String, Object>) properties.get("status");
        return (List<String>) status.get("enum");
    }
}
