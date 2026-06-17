package csbot.csbot.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class FaqSearchTools {

    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final VectorStore vectorStore;

    @Tool(description = """
            환불/배송/구매규칙 등 정책·FAQ 질문에 답하기 위해 사내 FAQ 문서를 검색합니다.
            언제 호출: 고객이 환불 기간, 배송비, 구매 제한 등 정책성 질문을 할 때 (본인 주문 데이터 조회가 아닌 일반 정책 질문).
            반환: 검색된 FAQ 항목(Q&A). 결과가 없으면 안내 문구.
            """)
    public String searchFaq(
            @ToolParam(description = "검색할 질문 또는 키워드") String query,
            @ToolParam(description = "카테고리: 환불정책, 배송정책, 구매규칙 중 하나. 모르면 호출하지 말고 빈 값으로 전달", required = false) String category) {
        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD);

            if (category != null && !category.isBlank()) {
                FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();
                requestBuilder.filterExpression(filterBuilder.eq("category", category).build());
            }

            List<Document> results = vectorStore.similaritySearch(requestBuilder.build());
            if (results.isEmpty()) {
                return "관련 FAQ를 찾지 못했습니다. 상담원 연결이 필요할 수 있습니다.";
            }
            return results.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception e) {
            log.warn("[FaqSearchTools] FAQ 검색 실패: {}", e.getMessage());
            return "FAQ 검색 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
