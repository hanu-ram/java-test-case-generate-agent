package cloudhalo.tech.javatestcasegenerateagent.rag;

import cloudhalo.tech.javatestcasegenerateagent.advisor.MyLoggingAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.tool.annotation.Tool;

@Slf4j
public class RagTool {

    private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;
    private final ChatClient.Builder chatClientBuilder;

    private RagTool(ChatClient.Builder chatClientBuilder, RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
        this.chatClientBuilder = chatClientBuilder;
    }

    @Tool(
            name = "organizationRulesRetriever",
            description = "Retrieve organization-specific business rules and domain constraints from the vector knowledge base before generating or repairing tests."
    )
    public String organizationContextLoader(String query) {
        String content = chatClientBuilder.build().prompt()
                .system("""
                        You are a helpful assistant that retrieves organization-specific business rules and domain constraints from the vector knowledge base.
                        And give AS IS. So that the user can use it directly. Do not add any imaginative rules by yourself or based on user query.
                        Format the context retrieved from vector database as a markdown so, that LLM can understand it even better.
                        At end add a line saying, The above are the organization-specific business rules and domain constraints you should adhere.
                        """)
                .advisors(retrievalAugmentationAdvisor, MyLoggingAdvisor.builder().build())
                .user(query)
                .call()
                .content();
        log.info("Retrieved content: {}", content);
        return content;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private ChatClient.Builder chatClientBuilder;
        private RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;

        public Builder chatClientBuilder(ChatClient.Builder chatClientBuilder) {
            this.chatClientBuilder = chatClientBuilder;
            return this;
        }

        public Builder retrievalAugmentationAdvisor(RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
            this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
            return this;
        }

        public RagTool build() {
            if (this.chatClientBuilder == null) {
                throw new IllegalStateException("chatClientBuilder is required");
            }
            if (this.retrievalAugmentationAdvisor == null) {
                throw new IllegalStateException("retrievalAugmentationAdvisor is required");
            }
            return new RagTool(this.chatClientBuilder, this.retrievalAugmentationAdvisor);
        }
    }

}
