package cloudhalo.tech.javatestcasegenerateagent.agent;

import cloudhalo.tech.javatestcasegenerateagent.advisor.MyLoggingAdvisor;
import cloudhalo.tech.javatestcasegenerateagent.analyzer.CodeMetadata;
import cloudhalo.tech.javatestcasegenerateagent.prompt.TestGenPromptBuilder;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

@Service
public class TestGenAgent {

    private final ChatClient chatClient;
    private final TestGenPromptBuilder promptBuilder;
    private final String agentModel;

    public TestGenAgent(
            ChatClient.Builder chatClientBuilder,
            TestGenPromptBuilder promptBuilder,
            ToolSearcher toolSearcher,
            @Value("${spring.ai.openai.chat.options.model}") String agentModel,
            @Value("${agent.model.knowledge.cutoff:2025-01-01}") String knowledgeCutoff,
            @Value("classpath:/prompts/prompt.st") Resource systemPrompt
    ) {
        this.promptBuilder = promptBuilder;
        this.agentModel = agentModel;

        this.chatClient = chatClientBuilder
                .defaultSystem(p -> p.text(systemPrompt)
                        .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                        .param(AgentEnvironment.AGENT_MODEL_KEY, agentModel)
                        .param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, knowledgeCutoff)
                )
                .defaultTools(
                        FileSystemTools.builder().build(),
                        GrepTool.builder().build(),
                        GlobTool.builder().build(),
                        ShellTools.builder().build(),
                        AskUserQuestionTool.builder()
                                .questionHandler(new CommandLineQuestionHandler())
                                .answersValidation(false)
                                .build()
                )
                .defaultAdvisors(
                        ToolSearchToolCallAdvisor.builder()
                                .conversationHistoryEnabled(true)
                                .toolSearcher(toolSearcher)
                                .maxResults(2)
                                .build(),
                        MyLoggingAdvisor.builder()
                                .showUserText(true)
                                .showAssistantText(true)
                                .showAvailableTools(true)
                                .build()
                )
                .build();
    }

    public GenerationResult generateTests(CodeMetadata metadata, Path workingDir) {
        return generateTests(metadata, workingDir, detectBuildTool(workingDir));
    }

    public GenerationResult generateTests(CodeMetadata metadata, Path workingDir, String buildTool) {
        System.out.println(banner());
        System.out.printf("  📋 Generating tests for: %s (%s)%n", metadata.className(), metadata.classType());
        System.out.printf("  🎯 Test slice : %s%n", metadata.suggestedTestSlice());
        System.out.printf("  📁 Output path: %s%n", metadata.suggestedTestPath());
        System.out.printf("  🔧 Build tool : %s%n%n", buildTool);

        String userPrompt = promptBuilder.buildUserPrompt(metadata, buildTool, workingDir.toString());

        String agentResponse = chatClient.prompt()
                .user(userPrompt)
                .system(s -> s.params(Map.of(
                        "workingDir", workingDir.toString(),
                        "package", metadata.packageName(),
                        "className", metadata.className())))
                .toolContext(Map.of(
                        // FIX 3: "workingDirectory" is what ShellTools reads as its CWD.
                        // Without this the shell runs from wherever the JVM started,
                        // gradlew/mvnw won't be found, and every command silently fails.
                        "workingDirectory", workingDir.toString(),
                        "targetClass", metadata.className(),
                        "testOutputPath", metadata.suggestedTestPath()))
                .call()
                .content();

        assert agentResponse != null;

        // SKIP response — entity/dto/pojo with no logic
        if (agentResponse.contains("<r>SKIP</r>")) {
            String reason = extractTag(agentResponse, "reason");
            System.out.println("  ⏭️  SKIPPED: " + reason);
            return new GenerationResult(true, metadata, agentResponse, 0, null, "SKIP: " + reason);
        }

        boolean passed = agentResponse.contains("<r>PASSED</r>");
        String summary = extractTag(agentResponse, "summary");
        String errors  = extractTag(agentResponse, "errors");

        // FIX 4: removed the heuristic fallback that was calling things "passed"
        // when they weren't. If the model didn't output <r>PASSED</r> it failed.
        // The heuristic was masking real failures and returning success=true incorrectly.
        if (passed) {
            System.out.println("  ✅ " + summary);
        } else {
            System.out.println("  ❌ " + (summary.isEmpty() ? "Test generation failed" : summary));
            if (!errors.isEmpty()) {
                System.out.println("  📋 " + errors);
            }
        }

        return new GenerationResult(passed, metadata, agentResponse, 0,
                metadata.suggestedTestPath(), errors.isEmpty() ? null : errors);
    }

    private String detectBuildTool(Path workingDir) {
        if (java.nio.file.Files.exists(workingDir.resolve("build.gradle")))     return "gradle";
        if (java.nio.file.Files.exists(workingDir.resolve("pom.xml")))          return "maven";
        if (java.nio.file.Files.exists(workingDir.resolve("build.gradle.kts"))) return "gradle";
        return "gradle";
    }

    private String extractTag(String text, String tag) {
        String open  = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = text.indexOf(open);
        int end   = text.indexOf(close);
        if (start == -1 || end == -1 || end <= start) return "";
        return text.substring(start + open.length(), end).trim();
    }

    public String call() {
        return chatClient.prompt()
                .system("You are a helpful assistant.")
                .user("Hello! what can you do for me")
                .call()
                .content();
    }

    public record GenerationResult(
            boolean success,
            CodeMetadata metadata,
            String agentResponse,
            int fixAttempts,
            String testFilePath,
            String errorSummary) {
    }

    private String banner() {
        return """
                
                ╔══════════════════════════════════════════════════════╗
                ║  TestGenAI  ·  Spring AI  ·  %-24s                   ║
                ╚══════════════════════════════════════════════════════╝
                """.formatted(agentModel);
    }
}