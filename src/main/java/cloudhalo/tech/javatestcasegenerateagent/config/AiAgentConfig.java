package cloudhalo.tech.javatestcasegenerateagent.config;

import org.springaicommunity.tool.search.ToolSearcher;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
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
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore) {
        VectorStoreDocumentRetriever vectorRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(3)
                .filterExpression(() -> new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("source"),
                        new Filter.Value("organization_rules.md")
                ))
                .build();
        return RetrievalAugmentationAdvisor.builder()
//                .queryTransformers(TranslationQueryTransformer.builder().chatClientBuilder(ChatClient.builder(openAiChatModel)).targetLanguage("English").build())
                .documentRetriever(vectorRetriever)
                .build();
    }

}
