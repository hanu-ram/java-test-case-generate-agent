package cloudhalo.tech.javatestcasegenerateagent.config;

import cloudhalo.tech.javatestcasegenerateagent.rag.reranker.NvidiaRerankerProperties;
import cloudhalo.tech.javatestcasegenerateagent.rag.reranker.NvidiaRerankingDocumentPostProcessor;
import cloudhalo.tech.javatestcasegenerateagent.rag.transformer.ContextSavingRewriteQueryTransformer;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Configuration
@EnableConfigurationProperties(NvidiaRerankerProperties.class)
public class AiAgentConfig {

    @Bean
    public ToolSearcher toolSearcher() {
        return new LuceneToolSearcher(0.5f);
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(MessageWindowChatMemory messageWindowChatMemory) {
        return MessageChatMemoryAdvisor
                .builder(messageWindowChatMemory)
                .order(Ordered.HIGHEST_PRECEDENCE)
                .build();
    }

    @Bean
    public MessageWindowChatMemory messageWindowChatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory
                .builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(10)
                .build();
    }

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.reranker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DocumentPostProcessor nvidiaRerankingDocumentPostProcessor(WebClient.Builder webClientBuilder,
                                                                      NvidiaRerankerProperties rerankerProperties) {
        return new NvidiaRerankingDocumentPostProcessor(webClientBuilder, rerankerProperties);
    }

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore,
                                                                    ChatClient.Builder chatClientBuilder,
                                                                    NvidiaRerankerProperties rerankerProperties,
                                                                    ObjectProvider<DocumentPostProcessor> documentPostProcessors) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        VectorStoreDocumentRetriever vectorRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(rerankerProperties.isEnabled() ? rerankerProperties.getCandidateCount() : 3)
                .similarityThreshold(0.3)
                .topK(3)
                .filterExpression(b.eq("source", "organization_rules.md").build())
              /*  .filterExpression(() -> new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("source"),
                        new Filter.Value("organization_rules.md")
                ))*/
                .build();
        var queryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder.defaultOptions(OpenAiChatOptions.builder().model("openai/gpt-oss-120b").temperature(0.4).build()))
                .promptTemplate(PromptTemplate.builder().template("""
                                    You are a query rewriting assistant.
                                
                                    Your task is to rewrite the user query for better retrieval from vector store (semantic search).
                                
                                    Target: {target}
                                
                                    If the query contains domain specific context or code,
                                    rewrite it to better match for fetching domain specific rules documents.
                                
                                    #Example:
                                    User query:
                                    ## TASK: Generate JUnit 5 Unit Tests
                                
                                    ### Target Class Analysis
                                    ```
                                    Class     : OwnerController
                                    Package   : com.owner
                                    Type      : CONTROLLER
                                    Build Tool: Gradle
                                    ```
                                Explanation: Above code and context clearly shows `Owner` domain specific information. So, organization vector store may have rules for owner domain specific validation for writing tests.
                                `Output`: Fetch the owner domain specific rules
                                
                                
                                    Original Query:
                                    {query}
                                
                                    Rewritten Query:
                                """)
                        .build())
                .targetSearchSystem("pinecone vector store")
                .build();

        List<DocumentPostProcessor> postProcessors = documentPostProcessors.orderedStream().toList();

        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(new ContextSavingRewriteQueryTransformer(queryTransformer))
                .documentRetriever(vectorRetriever)
                .documentPostProcessors(postProcessors)
                .build();
    }

}
