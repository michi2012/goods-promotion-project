package aiops.aiops.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class DltTools {

    private final RestClient serverAAdminClient;
    private final ObjectMapper objectMapper;

    public DltTools(
            @Qualifier("serverAAdminClient") RestClient serverAAdminClient,
            ObjectMapper objectMapper) {
        this.serverAAdminClient = serverAAdminClient;
        this.objectMapper = objectMapper;
    }

    @Tool(description = """
            serverA의 UNRESOLVED DLT(Dead Letter Table) 레코드 전체를 조회합니다.
            언제 호출: PurchaseDltAccumulated 알람 수신 시 가장 먼저 호출하세요.
            반환: id, orderId, goodsId, quantity, reason, status 필드를 포함한 JSON 배열.
            재처리 가능 여부 판단 기준:
              - retryable: orderId != "UNKNOWN" && goodsId != null → retryDlt(id) 호출
              - non-retryable: orderId == "UNKNOWN" 또는 goodsId == null → 스키마 오류, 수동 처리 필요
            실패 시: serverA 기동 여부를 확인하세요.
            """)
    public String listUnresolvedDlt() {
        try {
            String response = serverAAdminClient.get()
                    .uri("/api/v1/admin/dlt")
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            int count = root.isArray() ? root.size() : 0;
            log.info("[DltTools] UNRESOLVED DLT 조회 완료: {}건", count);
            return response;
        } catch (Exception e) {
            log.warn("[DltTools] UNRESOLVED DLT 조회 실패: {}", e.getMessage());
            return "UNRESOLVED DLT 조회 실패 (serverA 장애 가능성): " + e.getMessage();
        }
    }

    @Tool(description = """
            특정 DLT 레코드를 재처리합니다. listUnresolvedDlt 결과에서 retryable로 판단된 항목만 호출하세요.
            언제 호출: orderId != "UNKNOWN" && goodsId != null인 항목에 대해서만 호출.
            효과: 해당 orderId의 재고(goodsId, quantity)를 원자적으로 복구하고 DLT를 RESOLVED로 변경.
            반환: 성공 메시지 또는 실패 원인.
            실패 원인:
              - AlreadyResolvedException: 이미 처리됨 (중복 호출 방지됨)
              - GoodsNotFoundException: goodsId가 DB에 없음 → 수동 처리 필요
            """)
    public String retryDlt(
            @ToolParam(description = "재처리할 DLT 레코드의 id (listUnresolvedDlt 결과의 id 필드)") Long dltId) {
        try {
            String response = serverAAdminClient.post()
                    .uri("/api/v1/admin/dlt/{dltId}/retry", dltId)
                    .retrieve()
                    .body(String.class);

            log.info("[DltTools] DLT 재처리 완료: dltId={}", dltId);
            return "DLT [" + dltId + "] 재처리 완료: " + response;
        } catch (Exception e) {
            log.warn("[DltTools] DLT 재처리 실패: dltId={}, error={}", dltId, e.getMessage());
            return "DLT [" + dltId + "] 재처리 실패: " + e.getMessage() + " — 수동 처리가 필요합니다.";
        }
    }
}
