package cloudhalo.tech.javatestcasegenerateagent.agent;

import cloudhalo.tech.javatestcasegenerateagent.advisor.MyLoggingAdvisor;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AIAgent {

    private final ChatClient chatClient;
    private final String agentModel;

    @Value("classpath:prompts/prompt.st")
    Resource prompt;

    public AIAgent(
            final ChatClient.Builder chatClientBuilder,
            MessageChatMemoryAdvisor messageChatMemoryAdvisor,
            @Value("classpath:/prompt/MAIN_AGENT_SYSTEM_PROMPT_V2.md") Resource systemPrompt,
            @Value("${agent.model.knowledge.cutoff:Unknown}") String agentModelKnowledgeCutoff,
            @Value("${spring.ai.vertex.ai.gemini.chat.options.model:Unknown}") String agentModel
    ) {
        this.agentModel = agentModel;
        this.chatClient = chatClientBuilder.defaultSystem(p -> p.text(
                                """
                                         You are a helpful assistant. Always answer as helpfully as possible.\s
                                         Here is useful information about the environment you are running in:
                                         <env>
                                         {ENVIRONMENT_INFO}
                                         </env>
                                         You are powered by the model: {AGENT_MODEL}
                                        \s
                                         Assistant knowledge cutoff is {AGENT_MODEL_KNOWLEDGE_CUTOFF}.
                                         Do What user asks for DO NOT do anything what user does not ask for.
                                         If you are not clear with the question you can use AskUserQuestionTool to get the input from the user.
                                         Do not make any hallucinations and imagine the things yourself, be authentic and best agent that user should praise you.
                                        \s""")
                        .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                        .param(AgentEnvironment.GIT_STATUS_KEY, AgentEnvironment.gitStatus())
                        .param(AgentEnvironment.AGENT_MODEL_KEY, agentModel)
                        .param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, agentModelKnowledgeCutoff)
                )
                .defaultTools(AskUserQuestionTool.builder()
                        .questionHandler(new CommandLineQuestionHandler())
                        .answersValidation(false)
                        .build())
                .defaultToolContext(Map.of())
                .defaultTools(
                        ShellTools.builder().build(),
                        FileSystemTools.builder().build(),
                        SmartWebFetchTool.builder(chatClientBuilder.clone().build()).build(),
                        GrepTool.builder().build()
//                        WindowsShellTool.builder().build()
                )
                .defaultAdvisors(
                        ToolCallAdvisor.builder()
                                .conversationHistoryEnabled(false)
                                .build(),
//                        messageChatMemoryAdvisor,
                        MyLoggingAdvisor.builder()
                                .showAvailableTools(true)
                                .showAssistantText(true)
                                .showUserText(true)
                                .build()
                )
                .build();

    }

    public String runAgent(String command) {
        String m = """
                  -------------------------------------------------------
                  |  TestGenAI  ·  Spring AI  ·  %s                      |
                  -------------------------------------------------------
                """.formatted(agentModel);
        System.out.println(m);
        return chatClient.prompt()
                .system(prompt)
                .user(command)
                .toolContext(Map.of("user-id", "hanu"))
                .call()
                .content();
    }

    public String askAI(String question) {
        return chatClient.prompt().user(question)
                .toolContext(Map.of("user-id", "hanu"))
                .call()
                .content();
    }

}
