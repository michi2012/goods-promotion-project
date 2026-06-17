package csbot.csbot.faq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FaqDocumentLoader implements ApplicationRunner {

    private static final String FAQ_LOCATION_PATTERN = "classpath:faq/*.yaml";

    private final VectorStore vectorStore;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Yaml yaml = new Yaml();
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(FAQ_LOCATION_PATTERN);
            List<Document> documents = new ArrayList<>();

            for (Resource resource : resources) {
                Map<String, Object> faqFile = yaml.load(resource.getInputStream());
                String category = (String) faqFile.get("category");
                @SuppressWarnings("unchecked")
                List<Map<String, String>> items = (List<Map<String, String>>) faqFile.get("items");

                for (Map<String, String> item : items) {
                    String question = item.get("question");
                    String content = "Q: " + question + "\nA: " + item.get("answer");
                    String id = UUID.nameUUIDFromBytes((category + ":" + question).getBytes(StandardCharsets.UTF_8)).toString();
                    documents.add(new Document(id, content, Map.of("category", category)));
                }
            }

            if (!documents.isEmpty()) {
                vectorStore.add(documents);
                log.info("[FaqDocumentLoader] FAQ 문서 {}건 임베딩 완료 (파일 {}개)", documents.size(), resources.length);
            }
        } catch (Exception e) {
            log.warn("[FaqDocumentLoader] FAQ 문서 로딩 실패: {}", e.getMessage());
        }
    }
}
