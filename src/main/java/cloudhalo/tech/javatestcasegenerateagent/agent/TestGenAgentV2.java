/*
package cloudhalo.tech.javatestcasegenerateagent.agent;

import cloudhalo.tech.javatestcasegenerateagent.agent.workflow.EvaluatorOptimizerWorkflow;
import cloudhalo.tech.javatestcasegenerateagent.agent.workflow.ParallelizationWorkflow;
import cloudhalo.tech.javatestcasegenerateagent.agent.workflow.RoutingWorkflow;
import cloudhalo.tech.javatestcasegenerateagent.analyzer.CodeMetadata;
import org.springaicommunity.agent.tools.*;
import org.springaicommunity.agent.utils.AgentEnvironment;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

*/
/**
 * TestGenAgent — CHAIN WORKFLOW orchestrator (Pattern 1)
 *
 * Composes all four agentic patterns into a sequential chain where each
 * link's output becomes the next link's input:
 *
 * ┌────────────────────────────────────────────────────────────────────┐
 * │  CodeMetadata (from JavaParser)                                    │
 * │       │                                                            │
 * │  [Link 1] RoutingWorkflow         — Pattern 2: Routing             │
 * │       │   CodeMetadata → TestGenerationPlan                        │
 * │       │   (class type, framework directive, method groups)         │
 * │       │                                                            │
 * │  [Link 2] ParallelizationWorkflow — Pattern 3: Parallelization     │
 * │       │   TestGenerationPlan → List<String> (test method blocks)   │
 * │       │   (concurrent LLM calls, one per MethodGroup)              │
 * │       │                                                            │
 * │  [Link 3] assemble()              — pure Java, zero LLM cost       │
 * │       │   List<String> → complete test class String                │
 * │       │   (adds class wrapper, imports, @Mock declarations)        │
 * │       │                                                            │
 * │  [Link 4] EvaluatorOptimizerWorkflow — Pattern 5: Eval-Optimizer   │
 * │       │   String → RefinedResponse (PASS/NEEDS_IMPROVEMENT loop)   │
 * │       │   (evaluator scores, optimizer improves, max 3 iterations) │
 * │       │                                                            │
 * │  [Link 5] writeAndVerify()        — tool-calling agent             │
 * │           RefinedResponse → file on disk (FileSystemTools)         │
 * │           + test run via ShellTools (self-healing if fails)        │
 * └────────────────────────────────────────────────────────────────────┘
 *
 * CONTEXT ENGINEERING strategies applied across the chain:
 *
 *   SELECT  — ParallelizationWorkflow: each parallel call receives ONLY its
 *             2-3 method signatures + scenarios, not the full class
 *
 *   ISOLATE — EvaluatorOptimizerWorkflow: evaluator and optimizer each have
 *             their own ChatClient with isolated context windows
 *
 *   WRITE   — assemble(): class structure, imports, @Mock declarations are
 *             computed in Java and written deterministically — not generated
 *             by LLM, saving ~500 tokens per call on boilerplate
 *
 *   COMPRESS — Link 3 (assembly) strips markdown fences and stray class wrappers
 *              that workers may emit, keeping the context clean for the evaluator
 *//*

@Service
public class TestGenAgent {

    private static final Logger log = LoggerFactory.getLogger(TestGenAgent.class);

    private final RoutingWorkflow routingWorkflow;
    private final ParallelizationWorkflow parallelizationWorkflow;
    private final EvaluatorOptimizerWorkflow evaluatorOptimizerWorkflow;
    private final ChatClient toolAgent;       // Link 5: writes file + runs test
    private final String agentModel;

    public TestGenAgent(
            ChatClient.Builder chatClientBuilder,
            RoutingWorkflow routingWorkflow,
            ParallelizationWorkflow parallelizationWorkflow,
            EvaluatorOptimizerWorkflow evaluatorOptimizerWorkflow,
            @Value("${spring.ai.vertex.ai.gemini.chat.options.model}") String agentModel,
            @Value("${agent.model.knowledge.cutoff:2025-01-01}") String knowledgeCutoff,
            @Value("classpath:/prompts/prompt.st") Resource systemPrompt
    ) {
        this.routingWorkflow              = routingWorkflow;
        this.parallelizationWorkflow      = parallelizationWorkflow;
        this.evaluatorOptimizerWorkflow   = evaluatorOptimizerWorkflow;
        this.agentModel                   = agentModel;

        // Tool agent — separate ChatClient with file + shell tools
        // Used only in Link 5 for writing and verification
        this.toolAgent = chatClientBuilder
                .defaultSystem(p -> p
                        .text(systemPrompt)
                        .param(AgentEnvironment.ENVIRONMENT_INFO_KEY, AgentEnvironment.info())
                        .param(AgentEnvironment.AGENT_MODEL_KEY, agentModel)
                        .param(AgentEnvironment.AGENT_MODEL_KNOWLEDGE_CUTOFF_KEY, knowledgeCutoff)
                )
                .defaultTools(
                        FileSystemTools.builder().build(),   // write the test file
                        GrepTool.builder().build(),          // find related source
                        GlobTool.builder().build(),          // locate files by pattern
                        ShellTools.builder().build(),        // run mvn/gradle test
                        AskUserQuestionTool.builder()
                                .questionHandler(new CommandLineQuestionHandler())
                                .answersValidation(false)
                                .build()
                )
                .defaultAdvisors(
                        ToolCallAdvisor.builder()
                                .conversationHistoryEnabled(false)
                                .build()
                )
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public GenerationResult generateTests(CodeMetadata metadata, Path workingDir) {
        System.out.println(banner());

        // ── Link 1: ROUTING ───────────────────────────────────────────────────
        print("[1/5] 🔀 Routing    →  %s".formatted(metadata.className()));
        TestGenerationPlan plan = routingWorkflow.route(metadata);
        print("      ✅ %s → %s | %d group(s)".formatted(
                metadata.classType(), plan.workerType(), plan.methodGroups().size()));

        // ── Link 2: PARALLELIZATION ───────────────────────────────────────────
        print("[2/5] ⚡ Parallel   →  %d concurrent call(s)".formatted(plan.methodGroups().size()));
        String systemPrompt = buildWorkerSystemPrompt(plan);
        List<String> testBlocks = parallelizationWorkflow.generateInParallel(plan, systemPrompt);
        print("      ✅ %d test block(s) generated".formatted(testBlocks.size()));

        // ── Link 3: ASSEMBLY (pure Java — zero LLM cost) ──────────────────────
        print("[3/5] 🔧 Assembling → test class");
        String assembled = assemble(testBlocks, plan);
        print("      ✅ Class assembled");

        // ── Link 4: EVALUATOR-OPTIMIZER ───────────────────────────────────────
        print("[4/5] ⚖️  Evaluating → quality loop");
        EvaluatorOptimizerWorkflow.RefinedResponse refined =
                evaluatorOptimizerWorkflow.loop(assembled, plan);
        refined.chainOfThought().forEach(step -> print("      │  " + step));
        print("      ✅ Quality gate done (%d iteration(s))".formatted(refined.chainOfThought().size()));

        // ── Link 5: WRITE + VERIFY ────────────────────────────────────────────
        print("[5/5] 💾 Writing    →  %s".formatted(metadata.suggestedTestPath()));
        String agentResponse = writeAndVerify(refined.solution(), plan, workingDir);
        print("      ✅ Done");

        return new GenerationResult(metadata, refined, agentResponse);
    }

    // ── Link 3: Assembly — deterministic Java, not LLM ───────────────────────
    //
    // CONTEXT ENGINEERING — WRITE strategy:
    // The class wrapper, all imports, and @Mock declarations are deterministically
    // built from CodeMetadata. The LLM never wastes tokens generating boilerplate.
    // Workers output ONLY @Test methods. This step stitches them into a compilable class.

    private String assemble(List<String> testBlocks, TestGenerationPlan plan) {
        CodeMetadata m = plan.metadata();

        String imports = """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.InjectMocks;
                import org.mockito.Mock;
                import org.mockito.junit.jupiter.MockitoExtension;
                import static org.assertj.core.api.Assertions.*;
                import static org.mockito.Mockito.*;
                import static org.mockito.ArgumentMatchers.*;
                """ + m.imports().stream()
                .filter(i -> !i.startsWith("java.lang"))
                .map(i -> "import " + i + ";")
                .reduce("", (a, b) -> a + "\n" + b);

        String mockFields = m.injectedFields().stream()
                .map(d -> "    @Mock\n    private %s %s;".formatted(d.fieldType(), d.fieldName()))
                .reduce("", (a, b) -> a + "\n\n" + b);

        String subjectField = "    @InjectMocks\n    private %s %s;".formatted(
                m.className(),
                Character.toLowerCase(m.className().charAt(0)) + m.className().substring(1));

        // Extract only @Test method bodies — strip any class wrapper workers may have emitted
        String methods = testBlocks.stream()
                .map(this::extractTestMethods)
                .reduce("", (a, b) -> a + "\n\n" + b);

        return """
               package %s;

               %s

               @ExtendWith(MockitoExtension.class)
               class %s {

               %s

               %s

               %s
               }
               """.formatted(
                m.testPackageName(),
                imports,
                m.testClassName(),
                mockFields,
                subjectField,
                methods.trim()
        );
    }

    */
/**
     * CONTEXT ENGINEERING — COMPRESS strategy:
     * Strip markdown fences and stray class wrappers before passing to evaluator.
     * Reduces noise and prevents evaluator confusion about structure.
     *//*

    private String extractTestMethods(String block) {
        // Remove ```java ... ``` fences
        String s = block.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl != -1) s = s.substring(nl + 1);
        }
        if (s.endsWith("```")) s = s.substring(0, s.lastIndexOf("```")).trim();

        // If worker accidentally returned a full class, extract body
        if (s.contains("class ") && s.contains("{")) {
            int start = s.indexOf('{') + 1;
            int end   = s.lastIndexOf('}');
            if (start < end) s = s.substring(start, end).trim();
        }
        return s;
    }

    // ── Link 5: Write + verify via tool-calling agent ─────────────────────────

    private String writeAndVerify(String testClass, TestGenerationPlan plan, Path workingDir) {
        return toolAgent.prompt()
                .user("""
                      A quality-approved JUnit 5 test class is ready to be written.

                      Step 1 — Write the file using FileSystemTools:
                        Path: `%s`
                        Create parent directories if needed.

                      Step 2 — Run the test using ShellTools from project root `%s`:
                        Maven:  mvn test -Dtest=%s --no-transfer-progress
                        Gradle: ./gradlew test --tests "%s.%s"

                      Step 3 — If compilation or test FAILS:
                        - Read the error, fix the specific issue
                        - Overwrite the file using FileSystemTools
                        - Re-run (max 2 retries)

                      Step 4 — Report: file path, test result, any fixes made.

                      ## Test class:
                      ```java
                      %s
                      ```
                      """.formatted(
                        plan.metadata().suggestedTestPath(),
                        workingDir,
                        plan.metadata().testClassName(),
                        plan.metadata().testPackageName(),
                        plan.metadata().testClassName(),
                        testClass
                ))
                .toolContext(Map.of("workingDirectory", workingDir.toString()))
                .call()
                .content();
    }

    // ── Worker system prompt — QA persona + framework directive ──────────────
    // CONTEXT ENGINEERING: frameworkDirective is class-type specific (from Router)
    // Workers see ONLY the rules relevant to their class type.

    private String buildWorkerSystemPrompt(TestGenerationPlan plan) {
        return """
               You are an elite Java QA Engineer generating JUnit 5 test methods.

               %s

               UNIVERSAL RULES (always apply):
               - BDD naming: should_[result]_when_[condition]()
               - AssertJ exclusively: assertThat(), assertThatThrownBy()
               - AAA structure: // Arrange  // Act  // Assert comments in every test
               - Never test plain getters/setters
               - Output ONLY @Test methods — no class wrapper, no imports, no @BeforeEach
               """.formatted(plan.frameworkDirective());
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record GenerationResult(
            CodeMetadata metadata,
            EvaluatorOptimizerWorkflow.RefinedResponse refinedResponse,
            String agentResponse
    ) {
        public boolean success() {
            return agentResponse != null
                    && !agentResponse.toLowerCase().contains("error")
                    && !agentResponse.toLowerCase().contains("failed");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void print(String msg) { System.out.println("  " + msg); }

    private String banner() {
        return """

               ╔══════════════════════════════════════════════════════════════╗
               ║  TestGenAI  ·  Agentic Chain Workflow  ·  %-18s║
               ╚══════════════════════════════════════════════════════════════╝
               """.formatted(agentModel);
    }
}*/
