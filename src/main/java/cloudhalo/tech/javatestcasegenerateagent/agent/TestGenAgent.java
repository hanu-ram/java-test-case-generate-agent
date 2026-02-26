
package cloudhalo.tech.javatestcasegenerateagent.agent;

import cloudhalo.tech.javatestcasegenerateagent.advisor.ChatMemoryAdvisor;
import cloudhalo.tech.javatestcasegenerateagent.advisor.MyLoggingAdvisor;
import cloudhalo.tech.javatestcasegenerateagent.analyzer.CodeMetadata;
import cloudhalo.tech.javatestcasegenerateagent.prompt.TestGenPromptBuilder;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springaicommunity.tool.search.ToolSearcher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
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

    private final String[] DEFAULT_SYSTEM_PROMPTS = {"""
            You are TestGenAI, an elite Java QA Engineering specialist and Test Architect with 15+ years of experience. You combine the rigor of a senior QA architect with the precision of a test-driven development master.
             Your Identity:
             - You think like a QA engineer FIRST, developer second.
             - You understand that tests exist to CATCH BUGS, not just achieve arbitrary code coverage.
             - You are an expert in the modern Java testing ecosystem: JUnit 5, Mockito 5, AssertJ 3, Spring Boot Test slices, Testcontainers, and WireMock.
             </system_role>
             
             <core_principles>
             Follow these strict test-writing principles:
             1. NAMING (BDD Style): Use `should_<expected_result>_when_<given_condition>()`. Tests must read like documentation.
             2. STRUCTURE: Always use the AAA pattern. Explicitly separate sections with `// Arrange`, `// Act`, and `// Assert` comments.
             3. ASSERTIONS: Use AssertJ exclusively (e.g., `assertThat(...)`). NEVER use bare `assertTrue` or `assertEquals`.
             4. MOCKING: Mock only what crosses architectural boundaries. Do not over-mock internal logic.
             5. ISOLATION: Standard unit tests must be FAST (< 100ms). No real I/O unless explicitly writing integration tests.
             6. COMPLETENESS: For every method, systematically test: the happy path, null inputs, empty collections, boundary/edge values, and exception scenarios.
             7. MUTATION AWARENESS: Write hyper-specific assertions that would fail if the implementation logic changed even slightly.
             8. NO TRIVIAL TESTS: Never test standard getters/setters unless they contain custom business logic.
             9. SELF-REVIEW: Before finalizing any test, silently ask yourself: "Would this test catch a subtle bug that a junior developer might introduce?"
             </core_principles>
             
             <spring_boot_guidelines>
             When testing Spring components, apply the correct slicing:
             - `@Service` → Pure Mockito unit test (DO NOT load the full Spring context).
             - `@RestController` → Use `@WebMvcTest` with `MockMvc`.
             - `@Repository` → Use `@DataJpaTest` (with an in-memory DB like H2 or Testcontainers).
             - `@Component` with `@Transactional` → Specifically test transaction rollback and commit behavior.
             - `Do not write Junit or spring boot test for @Entity, Model, DTO, @Configuration classes unless they contain complex logic. Focus on testing business logic in @Service and @RestController layers.
             - `If you encounter such classes simply skip to writing test and immediately return response in single like <result>SKIP</result><reason>one liner reason</reason>
             </spring_boot_guidelines>
            """,
            """
            <role>
            You are a file system agent. Your only job is to write a Java source file
            to the correct path on disk.
            </role>
            
            create a file with given data use tool search tool to search relevant tool and get the job done.
            Without creating file don't return path if file created successfully then only return file path as response
            """,
            """
            <role>
            You are a build and test execution agent. Your only job is to run a specific
            test class and report the result with precision. Use tool search tool to search relevant tool and get the job done.
            </role>
            <instructions>
            You will receive:
            - <test_class_name> — simple class name (e.g. UserServiceTest)
            - <test_package>    — fully qualified package (e.g. com.example.service)
            - <working_dir>     — project root path
            - <build_tool>      — "maven" or "gradle"
            <environment_context>
            You are running on windows
            </environment_context>
            Steps:
            1. Run the test using the correct command:
               Maven : mvn test -Dtest=<test_class_name> -pl . --no-transfer-progress
               Gradle: ./gradlew test --tests "<test_package>.<test_class_name>" --info
            2. Capture the full output.
            3. Determine the result: PASSED or FAILED.
            
           <instruction>
           - `DO NOT execute command like ./gradlew test --tests \\"org.springframework.samples.meow.owner.ServiceTest\\" --info"`
           - `execute like: cd /d "E:\\spring-petclinic" && gradlew test --tests "org.springframework.samples.petclinic.owner.OwnerTest" --info in single line`
           - `If command is failing think & ask yourself why its failing and heal and correct yourself.
           - `Return only if you get response of the test cases whether it failed or passed`
           </instruction>
            Output format (strict):
            <result>PASSED|FAILED</result>
            <summary>one line: e.g. "5 tests passed" or "2 tests failed"</summary>
            <errors>if FAILED: paste only the relevant error lines. if PASSED: empty.</errors>
            </instructions>
            """,
            """
            <role>
            You are a Java test repair agent. You receive a failing test class and its
            error output and return a corrected version of the test class.
            </role>
            
            <instructions>
            You will receive:
            - <test_code>    — the current test class source that failed
            - <errors>       — compiler or runtime error output from the test run
            - <source_code>  — the original source class under test (for reference)
            - <metadata>     — class name, package, dependencies
            
            Chain of thought before fixing:
            1. Read the error output and identify the root cause precisely.
            2. Locate the exact line(s) in the test class responsible.
            3. Determine the minimal change needed — do not rewrite what is working.
            4. Apply the fix.
            
            Output rules:
            - Return ONLY the corrected raw Java source code. No markdown, no explanation.
            - Return the full file (not a diff). Agent 2 will overwrite the file with it.
            - Maximum 2 fix attempts. If the error cannot be resolved, output:
              UNRESOLVABLE: <one line reason>
            </instructions>
            """
    };
    private final ChatClient chatClient;
    private final TestGenPromptBuilder promptBuilder;
    private final String agentModel;

    public TestGenAgent(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            TestGenPromptBuilder promptBuilder,
            ToolSearcher toolSearcher,
            @Value("${spring.ai.openai.chat.options.model}") String agentModel,
            @Value("${agent.model.knowledge.cutoff:2025-01-01}") String knowledgeCutoff,
            @Value("classpath:/prompts/prompt.st") Resource systemPrompt
    ) {
        this.promptBuilder = promptBuilder;
        this.agentModel = agentModel;
        final var summaryClient = chatClientBuilder.build();
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
                                .build(),
                        new ChatMemoryAdvisor(chatMemory, summaryClient)
                )
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIMARY ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generate JUnit tests for an already-analyzed class.
     * <p>
     * The user prompt explicitly instructs Claude to:
     * 1. Use FileSystemTools to read dependency source files if needed
     * 2. Generate the complete test class
     * 3. Use FileSystemTools.writeFile() to write it to metadata.suggestedTestPath()
     * 4. Use ShellTools to run the test and verify it passes
     * 5. If test fails — fix and re-run (self-healing, max 2 retries)
     *
     * @param metadata   structured class analysis from JavaParser
     * @param workingDir project root — passed to ShellTools so mvn/gradle runs correctly
     * @return generation result with outcome details
     */

    public GenerationResult generateTests(CodeMetadata metadata, Path workingDir) {
        System.out.println(banner());
        System.out.printf("  📋 Generating tests for: %s (%s)%n",
                metadata.className(), metadata.classType());
        System.out.printf("  🎯 Test slice : %s%n", metadata.suggestedTestSlice());
        System.out.printf("  📁 Output path: %s%n%n", metadata.suggestedTestPath());

        String userPrompt = promptBuilder.buildUserPrompt(metadata);

        String agentResponse = chatClient.prompt()
                .user(userPrompt)
                .system(s -> s.params(Map.of(
                        "workingDir", workingDir.toString(),
                        "package",    metadata.packageName(),
                        "className",  metadata.className()
                )))
                .toolContext(Map.of(
                        "workingDirectory", workingDir.toString(),
                        "targetClass", metadata.className(),
                        "testOutputPath", metadata.suggestedTestPath()
                ))
                .call()
                .content();

        assert agentResponse != null;
        boolean success = !agentResponse.toLowerCase().contains("error")
                && !agentResponse.toLowerCase().contains("failed")
                && !agentResponse.toLowerCase().contains("could not");

        return new GenerationResult(success, metadata, agentResponse);
    }

    /**
     * This method used multi agent step for single class
     * @return
     */
   /* public GenerationResult generateTests(CodeMetadata metadata, Path workingDir) {
        System.out.println(banner());
        System.out.printf("  📋 Generating tests for: %s (%s)%n",
                metadata.className(), metadata.classType());
        System.out.printf("  🎯 Test slice : %s%n", metadata.suggestedTestSlice());
        System.out.printf("  📁 Output path: %s%n%n", metadata.suggestedTestPath());

        // ── Agent 1: Generate test code ───────────────────────────────────────
        String userPrompt = promptBuilder.buildUserPrompt(metadata);
        System.out.printf("\nSTEP 1 (input):\n %s%n", userPrompt);

        String testCode = chatClient.prompt(
                String.format("{%s}\n {%s}", DEFAULT_SYSTEM_PROMPTS[0], userPrompt)
        ).call().content();
        assert testCode != null;
        if (testCode.contains("SKIP")) {
            return new GenerationResult(true, metadata, testCode);
        }
        System.out.printf("\nSTEP 1 (output):\n %s%n", testCode);

        // ── Agent 2: Write file to disk ───────────────────────────────────────
        String fileWriterInput = """
            <test_code>%s</test_code>
            <test_path>%s</test_path>
            """.formatted(testCode, metadata.suggestedTestPath());
        System.out.printf("\nSTEP 2 (input):\n %s%n", fileWriterInput);

        String writtenPath = chatClient.prompt(
                String.format("{%s}\n {%s}", DEFAULT_SYSTEM_PROMPTS[1], fileWriterInput)
        ).call().content();
        System.out.printf("\nSTEP 2 (output):\n %s%n", writtenPath);

        // ── Agent 3 + Agent 4 retry loop ──────────────────────────────────────
        String currentTestCode = testCode;
        String runnerOutput    = null;
        final int MAX_FIX_ATTEMPTS = 2;

        for (int attempt = 0; attempt <= MAX_FIX_ATTEMPTS; attempt++) {

            // ── Agent 3: Run the test ─────────────────────────────────────
            String runnerInput = """
                <test_class_name>%s</test_class_name>
                <test_package>%s</test_package>
                <working_dir>%s</working_dir>
                <build_tool>%s</build_tool>
                """.formatted(
                    metadata.testClassName(),
                    metadata.testPackageName(),
                    workingDir,
                    "gradle"
            );
            System.out.printf("\nSTEP 3 attempt-%d (input):\n %s%n", attempt, runnerInput);

            runnerOutput = chatClient.prompt(
                    String.format("{%s}\n {%s}", DEFAULT_SYSTEM_PROMPTS[2], runnerInput)
            ).call().content();
            System.out.printf("\nSTEP 3 attempt-%d (output):\n %s%n", attempt, runnerOutput);

            // ── PASS → exit ───────────────────────────────────────────────
            if (runnerOutput != null && runnerOutput.contains("<result>PASSED</result>")) {
                System.out.println("\n✅ Tests passed! Exiting.");
                return new GenerationResult(true, metadata, runnerOutput);
            }

            // ── Max retries reached → give up ─────────────────────────────
            if (attempt == MAX_FIX_ATTEMPTS) {
                System.out.println("\n❌ Max fix attempts reached. Exiting.");
                break;
            }

            // ── Agent 4: Fix the broken test ──────────────────────────────
            String fixInput = """
                <test_code>%s</test_code>
                <errors>%s</errors>
                <source_code>%s</source_code>
                <metadata>class=%s package=%s</metadata>
                """.formatted(
                    currentTestCode,
                    runnerOutput,          // full runner output — Agent 4 extracts errors itself
                    metadata.rawSource(),
                    metadata.className(),
                    metadata.testPackageName()
            );
            System.out.printf("\nSTEP 4 attempt-%d (input):\n %s%n", attempt, fixInput);

            String fixedCode = chatClient.prompt(
                    String.format("{%s}\n {%s}", DEFAULT_SYSTEM_PROMPTS[3], fixInput)
            ).call().content();
            System.out.printf("\nSTEP 4 attempt-%d (output):\n %s%n", attempt, fixedCode);

            // ── Unresolvable error → give up ──────────────────────────────
            if (fixedCode == null || fixedCode.startsWith("UNRESOLVABLE")) {
                System.out.printf("\n❌ %s%n", fixedCode);
                return new GenerationResult(false, metadata, fixedCode);
            }

            // ── Overwrite file with fix, then loop back to Agent 3 ────────
            currentTestCode = fixedCode;
            String overwriteInput = """
                <test_code>%s</test_code>
                <test_path>%s</test_path>
                """.formatted(fixedCode, metadata.suggestedTestPath());

            chatClient.prompt(
                    String.format("{%s}\n {%s}", DEFAULT_SYSTEM_PROMPTS[1], overwriteInput)
            ).call().content();
            System.out.printf("\nFile overwritten with fix (attempt %d)%n", attempt + 1);
        }

        assert runnerOutput != null;
        boolean success = runnerOutput.contains("<result>PASSED</result>");
        return new GenerationResult(success, metadata, runnerOutput);
    }*/


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
            String agentResponse
    ) {
    }

    private String banner() {
        return """
                
                ╔══════════════════════════════════════════════════════╗
                ║  TestGenAI  ·  Spring AI  ·  %-24s                   ║
                ╚══════════════════════════════════════════════════════╝
                """.formatted(agentModel);
    }
}
