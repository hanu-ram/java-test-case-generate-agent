package cloudhalo.tech.javatestcasegenerateagent.advisor;

import lombok.AllArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.DEFAULT_CONVERSATION_ID;

@AllArgsConstructor
public class ChatMemoryAdvisor implements BaseChatMemoryAdvisor {

    private final ChatMemory chatMemory;
    private final ChatClient summarizerClient;
    private static final int THRESHOLD = 6;
    private static final int MAX_HISTORY = 3;

    @Override
    public @NonNull ChatClientRequest before(ChatClientRequest request, @NonNull AdvisorChain advisorChain) {
        final var conversationId = getConversationId(request.context(), DEFAULT_CONVERSATION_ID);
        List<Message> history = this.chatMemory.get(conversationId);
        final var history_size = history.size();
        if(history_size>THRESHOLD){
            final var oldHistory = history.subList(0, history_size-MAX_HISTORY);
            final var recentHistory = history.subList(history_size-MAX_HISTORY, history_size);

            final var summary = summarizerClient.prompt()
                    .system("You are a Technical Auditor. Summarize the Java testing progress. " +
                            "List: 1. Target Class, 2. Methods Covered, 3. Last Compiler Error, 4. Mocked Dependencies. " +
                            "Be extremely concise. Use bullet points.")
                    .user("Summarize this history: " + oldHistory)
                    .call();
            chatMemory.clear(conversationId);
            chatMemory.add(conversationId, new SystemMessage("PAST_CONTEXT: " + summary));
            chatMemory.add(conversationId, recentHistory);

            history = this.chatMemory.get(conversationId);
        }
        final var finalPrompt = """
                # CONTEXT LEDGER
                %s
                
                # INSTRUCTION
                Using the context above, perform the following task. 
                If the ledger indicates a previous failure, do not repeat the same approach.
                
                Task: %s
                """.formatted(history, request.prompt().getUserMessages());
        return ChatClientRequest.builder()
                .prompt(request.prompt())
                .build();

    }

    @Override
    public @NonNull ChatClientResponse after(ChatClientResponse chatClientResponse, @NonNull AdvisorChain advisorChain) {
        List<Message> assistantMessages = new ArrayList<>();
        if (chatClientResponse.chatResponse() != null) {
            assistantMessages = chatClientResponse.chatResponse()
                    .getResults()
                    .stream()
                    .map(g -> (Message) g.getOutput())
                    .toList();
        }
        this.chatMemory.add(this.getConversationId(chatClientResponse.context(), DEFAULT_CONVERSATION_ID),
                assistantMessages);
        return chatClientResponse;
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
