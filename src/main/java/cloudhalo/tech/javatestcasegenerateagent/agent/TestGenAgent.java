
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

/**
 * TestGenAgent — the core LLM chain for JUnit test generation.
 * <p>
 * Flow:
 * [JavaParser → CodeMetadata] → [PromptBuilder → user prompt] → [ChatClient + Tools] → Claude writes file
 * <p>
 * Tools available to Claude during generation:
 * <p>
 * FileSystemTools  — Claude reads dependency source files AND writes the final test file.
 * Claude knows the exact target path from metadata.suggestedTestPath()
 * in the prompt, so it calls FileSystemTools.writeFile() directly.
 * No separate Java TestFileWriter needed.
 * <p>
 * GrepTool         — Claude searches for usages, related constants, base class methods.
 * <p>
 * GlobTool         — Claude finds related source files (DTOs, entities, exceptions)
 * referenced in method signatures that it needs to understand fully.
 * <p>
 * ShellTools       — Claude runs the test after writing it:
 * mvn test -Dtest=UserServiceTest -pl .
 * ./gradlew test --tests "com.acme.UserServiceTest"
 * If it fails, Claude reads the error, fixes the test, re-runs.
 * This is the self-healing loop — no human needed.
 * <p>
 * AskUserQuestionTool — Claude asks for clarification if a type cannot be resolved.
 */

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
                                .conversationHistoryEnabled(false)
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

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate JUnit tests for an already-analyzed class.
     * <p>
     * The enriched user prompt (from TestGenPromptBuilder) instructs the LLM to:
     * 1. Read dependency source files if needed (FileSystemTools + GlobTool)
     * 2. Generate the complete test class
     * 3. Write to metadata.suggestedTestPath() (FileSystemTools)
     * 4. Run the test (ShellTools) and self-heal if it fails (max 3 retries)
     * 5. Report structured result: {@code <result>PASSED|FAILED</result>}
     *
     * @param metadata   structured class analysis from JavaParser
     * @param workingDir project root — passed to ShellTools so mvn/gradle runs
     *                   correctly
     * @return generation result with outcome details
     */

    public GenerationResult generateTests(CodeMetadata metadata, Path workingDir) {
        return generateTests(metadata, workingDir, detectBuildTool(workingDir));
    }

    /**
     * Generate JUnit tests with explicit build tool specification.
     *
     * @param metadata   structured class analysis from JavaParser
     * @param workingDir project root
     * @param buildTool  "gradle" or "maven"
     * @return generation result with outcome details
     */
    public GenerationResult generateTests(CodeMetadata metadata, Path workingDir, String buildTool) {
        System.out.println(banner());
        System.out.printf("  📋 Generating tests for: %s (%s)%n",
                metadata.className(), metadata.classType());
        System.out.printf("  🎯 Test slice : %s%n", metadata.suggestedTestSlice());
        System.out.printf("  📁 Output path: %s%n", metadata.suggestedTestPath());
        System.out.printf("  🔧 Build tool : %s%n%n", buildTool);

        // Build the enriched user prompt with Steps 1-4 (incl. write file + run +
        // self-heal)
        String userPrompt = promptBuilder.buildUserPrompt(metadata, buildTool, workingDir.toString());

        String agentResponse = chatClient.prompt()
                .user(userPrompt)
                .system(s -> s.params(Map.of(
                        "workingDir", workingDir.toString(),
                        "package", metadata.packageName(),
                        "className", metadata.className())))
                .toolContext(Map.of(
                        "workingDirectory", workingDir.toString(),
                        "targetClass", metadata.className(),
                        "testOutputPath", metadata.suggestedTestPath()))
                .call()
                .content();

        assert agentResponse != null;

        // ── Structured result parsing ──────────────────────────────────────
        // Check for SKIP responses (e.g. Entity, DTO classes)
        if (agentResponse.contains("<result>SKIP</result>")) {
            String reason = extractTag(agentResponse, "reason");
            System.out.println("  ⏭️  SKIPPED: " + reason);
            return new GenerationResult(true, metadata, agentResponse, 0,
                    null, "SKIP: " + reason);
        }

        // Parse structured tags from the agent response
        boolean passed = agentResponse.contains("<result>PASSED</result>");
        String summary = extractTag(agentResponse, "summary");
        String errors = extractTag(agentResponse, "errors");

        if (passed) {
            System.out.println("  ✅ " + summary);
        } else {
            // Fallback: if no structured tags, use heuristic
            if (!agentResponse.contains("<result>")) {
                // Old-style unstructured response — use heuristic
                passed = !agentResponse.toLowerCase().contains("compilation failed")
                        && !agentResponse.toLowerCase().contains("build failed")
                        && !agentResponse.toLowerCase().contains("test failed");
                errors = passed ? "" : "Unstructured response — couldn't determine result tags";
            }
            if (!passed) {
                System.out.println("  ❌ " + (summary.isEmpty() ? "Test generation failed" : summary));
            }
        }

        return new GenerationResult(passed, metadata, agentResponse, 0,
                metadata.suggestedTestPath(), errors.isEmpty() ? null : errors);
    }

    /**
     * Detect build tool from the project root directory.
     */
    private String detectBuildTool(Path workingDir) {
        if (java.nio.file.Files.exists(workingDir.resolve("build.gradle")))
            return "gradle";
        if (java.nio.file.Files.exists(workingDir.resolve("pom.xml")))
            return "maven";
        if (java.nio.file.Files.exists(workingDir.resolve("build.gradle.kts")))
            return "gradle";
        return "gradle"; // default
    }

    /**
     * Extract content between XML-like tags from agent response.
     * e.g. extractTag(response, "summary") → content inside <summary>...</summary>
     */
    private String extractTag(String text, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int start = text.indexOf(open);
        int end = text.indexOf(close);
        if (start == -1 || end == -1 || end <= start)
            return "";
        return text.substring(start + open.length(), end).trim();
    }

    public String call() {
        return chatClient.prompt()
                .system("You are a helpful assistant.")
                .user("Hello! what can you do for me")
                .call()
                .content();
    }

    /**
     * Result of test generation.
     *
     * @param success       true if all tests passed or class was skipped
     * @param metadata      the analyzed class metadata
     * @param agentResponse raw LLM response
     * @param fixAttempts   number of fix attempts made (0 if passed first time)
     * @param testFilePath  path to the generated test file (null if skipped)
     * @param errorSummary  error details if failed (null if success)
     */
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
