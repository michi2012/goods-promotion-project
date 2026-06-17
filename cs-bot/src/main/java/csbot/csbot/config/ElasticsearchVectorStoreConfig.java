package csbot.csbot.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

@Configuration
public class ElasticsearchVectorStoreConfig {

    @Value("${spring.elasticsearch.uris}")
    private String elasticsearchUris;

    @Value("${spring.ai.vectorstore.elasticsearch.index-name}")
    private String indexName;

    @Value("${spring.ai.vectorstore.elasticsearch.dimensions}")
    private int dimensions;

    @Bean
    public RestClient elasticsearchRestClient() {
        URI uri = URI.create(elasticsearchUris);
        return RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme())).build();
    }

    @Bean
    public ElasticsearchVectorStore vectorStore(RestClient elasticsearchRestClient, EmbeddingModel embeddingModel) {
        ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
        options.setIndexName(indexName);
        options.setDimensions(dimensions);
        options.setSimilarity(SimilarityFunction.cosine);

        return ElasticsearchVectorStore.builder(elasticsearchRestClient, embeddingModel)
                .options(options)
                .initializeSchema(true)
                .build();
    }
}
