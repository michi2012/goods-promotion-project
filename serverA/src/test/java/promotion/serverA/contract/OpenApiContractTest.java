package promotion.serverA.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * serverA가 실제로 노출하는 OpenAPI 스펙(/v3/api-docs)이 계약 원본(contracts/server-a-openapi.yaml)과
 * 어긋나지 않는지 확인한다. 계약은 사람이 먼저 쓰는 쪽이 원본이므로, 여기서 실패하면
 * 계약을 고칠지 구현을 고칠지는 사람이 판단한다 (자동으로 어느 쪽이 맞다고 판정하지 않음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    private static final String CONTRACT_PATH = "../contracts/server-a-openapi.yaml";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("POST /api/v1/promotions/purchase 요청 스키마가 계약과 일치한다")
    void purchaseEndpointMatchesContract() throws Exception {
        Map<String, Object> live = fetchLiveSpec();
        Map<String, Object> contract = loadContract();

        Map<String, Object> liveSchema = resolveRequestBodySchema(live, "/api/v1/promotions/purchase", "post");
        Map<String, Object> contractSchema = resolveRequestBodySchema(contract, "/api/v1/promotions/purchase", "post");

        assertThat(propertyNames(live, liveSchema))
                .as("PurchaseRequest 필드가 계약과 일치해야 한다")
                .isEqualTo(propertyNames(contract, contractSchema));
    }

    @Test
    @DisplayName("GET /api/v1/goods/{goodsId} 응답 스키마가 계약과 일치한다")
    void getGoodsEndpointMatchesContract() throws Exception {
        Map<String, Object> live = fetchLiveSpec();
        Map<String, Object> contract = loadContract();

        Map<String, Object> liveSchema = resolveResponseSchema(live, "/api/v1/goods/{goodsId}", "get", "200");
        Map<String, Object> contractSchema = resolveResponseSchema(contract, "/api/v1/goods/{goodsId}", "get", "200");

        assertThat(propertyNames(live, liveSchema))
                .as("GoodsResponse 필드가 계약과 일치해야 한다")
                .isEqualTo(propertyNames(contract, contractSchema));
    }

    private Map<String, Object> fetchLiveSpec() throws Exception {
        String liveJson = restTemplate.getForObject("http://localhost:" + port + "/v3/api-docs", String.class);
        return new ObjectMapper().readValue(liveJson, Map.class);
    }

    private Map<String, Object> loadContract() throws Exception {
        try (FileInputStream in = new FileInputStream(CONTRACT_PATH)) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRequestBodySchema(Map<String, Object> spec, String path, String method) {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        Map<String, Object> requestBody = (Map<String, Object>) operation.get("requestBody");
        Map<String, Object> content = (Map<String, Object>) requestBody.get("content");
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        return (Map<String, Object>) json.get("schema");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveResponseSchema(Map<String, Object> spec, String path, String method, String status) {
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
        Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
        Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
        Map<String, Object> response = (Map<String, Object>) responses.get(status);
        Map<String, Object> content = (Map<String, Object>) response.get("content");
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        return (Map<String, Object>) json.get("schema");
    }

    @SuppressWarnings("unchecked")
    private Set<String> propertyNames(Map<String, Object> spec, Map<String, Object> schema) {
        String ref = (String) schema.get("$ref");
        String schemaName = ref.substring(ref.lastIndexOf('/') + 1);
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> target = (Map<String, Object>) schemas.get(schemaName);
        Map<String, Object> properties = (Map<String, Object>) target.get("properties");
        return properties.keySet();
    }
}
