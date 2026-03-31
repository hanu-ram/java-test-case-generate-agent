package cloudhalo.tech.javatestcasegenerateagent.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.tool.annotation.Tool;

public class RagTool {

    private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;
    private final ChatClient.Builder chatClientBuilder;

    private RagTool(ChatClient.Builder chatClientBuilder, RetrievalAugmentationAdvisor retrievalAugmentationAdvisor) {
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
        this.chatClientBuilder = chatClientBuilder;
    }

    @Tool(
            name = "organizationRulesRetriever",
            description = "Retrieve organization-specific business rules and domain constraints from the vector knowledge base before generating or repairing tests.",
            returnDirect = true
    )
    public String organizationContextLoader(String query) {
        return chatClientBuilder.clone().build().prompt()
                .advisors(retrievalAugmentationAdvisor)
                .user(query)
                .call()
                .content();
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
