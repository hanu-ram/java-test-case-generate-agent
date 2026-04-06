package cloudhalo.tech.javatestcasegenerateagent.prompt;

import cloudhalo.tech.javatestcasegenerateagent.analyzer.CodeMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the structured USER prompt sent to Claude for test generation.
 * <p>
 * The SYSTEM prompt (QA persona, rules) is loaded from
 * TESTGEN_SYSTEM_PROMPT.st.
 * This class builds the USER prompt — the structured, JavaParser-derived
 * context
 * that tells the LLM exactly WHAT to test.
 * <p>
 * Design principle:
 * Send STRUCTURED METADATA (from JavaParser) not raw source.
 * The LLM gets exactly the signal it needs — nothing more.
 * Raw source is attached separately only for implementation reference.
 */
@Component
public class TestGenPromptBuilder {

    /**
     * Build the user prompt from analyzed code metadata.
     * This is a rich, structured prompt — not just "generate tests for X".
     */
    public String buildUserPrompt(CodeMetadata metadata) {
        return buildUserPrompt(metadata, "gradle", null);
    }

    /**
     * Build an enriched user prompt including build-tool-aware run instructions.
     *
     * @param metadata   structured class analysis from JavaParser
     * @param buildTool  "gradle" or "maven"
     * @param workingDir project root (for run command context)
     */
    public String buildUserPrompt(CodeMetadata metadata, String buildTool, String workingDir) {
        return """
                ## TASK: Generate JUnit 5 Unit Tests
                
                ### Target Class Analysis
                - Class: %s
                - Package: %s
                - Type: %s
                - Test Slice: %s
                - Test Class: %s
                - Test Path: %s
                - Build Tool: %s
                - Source File: %s
                
                ### Class Context
                %s
                
                %s
                
                ### Public Methods to Test
                %s
                
                ### Dependencies to Mock
                %s
                
                ### Identified Test Scenarios
                %s
                
                ### Important
                - Read the target source file before generating tests.
                - Read dependency classes if method signatures or failures require more context.
                - Use the organization-rules retrieval tool when the class, package, or scenarios imply domain-specific behavior.
                - Keep domain-rule assertions even if the current implementation does not yet satisfy them.
                """.formatted(
                metadata.className(),
                metadata.packageName(),
                metadata.classType(),
                metadata.suggestedTestSlice(),
                metadata.testClassName(),
                metadata.suggestedTestPath(),
                buildTool,
                metadata.absolutePath(),
                formatAnnotations(metadata.classAnnotations()),
                formatInterfaces(metadata),
                formatMethods(metadata.publicMethods()),
                formatDependencies(metadata.injectedFields()),
                buildTestScenarios(metadata));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCENARIO BUILDER — This is key: we pre-compute scenarios from metadata
    // so the LLM doesn't have to guess what edge cases exist.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pre-compute test scenarios from metadata analysis.
     * This guides the LLM to generate complete, non-trivial tests.
     * It is QA thinking applied programmatically before the LLM runs.
     */
    private String buildTestScenarios(CodeMetadata metadata) {
        StringBuilder sb = new StringBuilder();

        for (CodeMetadata.MethodInfo method : metadata.publicMethods()) {
            sb.append("\n#### `").append(method.methodName()).append("()`\n");

            // Happy path — always
            sb.append("- ✅ Happy path: valid input → expected ").append(
                    method.isVoid() ? "behavior verified via mock interaction" : "return value asserted").append("\n");

            // Null checks — for non-primitive parameters
            if (!method.parameters().isEmpty()) {
                sb.append("- ⚠️  Null input: null parameter → ")
                        .append(method.thrownExceptions().isEmpty()
                                ? "NullPointerException or graceful handling"
                                : method.thrownExceptions().get(0))
                        .append("\n");
            }

            // Exception path — for each declared exception
            for (String ex : method.thrownExceptions()) {
                sb.append("- ❌ Exception path: condition that triggers `")
                        .append(ex).append("` → verify message and type\n");
            }

            // Conditional logic — drives additional branch tests
            if (method.hasConditionalLogic()) {
                sb.append("- 🔀 Branch coverage: test each conditional branch separately\n");
            }

            // Loop tests — empty collection edge case
            if (method.hasLoops()) {
                sb.append("- 📋 Empty collection: empty input → verify empty result or no-op\n");
            }

            // Void method mock verification
            if (method.isVoid()) {
                sb.append("- 🔍 Interaction test: verify mock dependencies called with correct args\n");
                sb.append("- 🔍 Negative test: verify mock NOT called when precondition fails\n");
            }

            // Optional return — not found case
            if (method.returnType().startsWith("Optional")) {
                sb.append("- 🔍 Not found: entity doesn't exist → empty Optional returned\n");
            }

            // List return — empty case
            if (method.returnType().startsWith("List") || method.returnType().startsWith("Set")) {
                sb.append("- 📋 Empty result: no matching data → empty collection (not null)\n");
            }
        }

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FORMATTERS
    // ─────────────────────────────────────────────────────────────────────────

    private String formatAnnotations(List<String> annotations) {
        if (annotations.isEmpty())
            return "  (none)";
        return annotations.stream()
                .map(a -> "  " + a)
                .collect(Collectors.joining("\n"));
    }

    private String formatInterfaces(CodeMetadata metadata) {
        if (metadata.classInterfaces().isEmpty() && metadata.superClass() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("### Inheritance\n");
        if (metadata.superClass() != null) {
            sb.append("  Extends  : ").append(metadata.superClass()).append("\n");
        }
        if (!metadata.classInterfaces().isEmpty()) {
            sb.append("  Implements: ").append(String.join(", ", metadata.classInterfaces())).append("\n");
        }
        return sb.toString();
    }

    private String formatMethods(List<CodeMetadata.MethodInfo> methods) {
        if (methods.isEmpty())
            return "  (no public methods found)";

        return methods.stream()
                .map(m -> """
                        Method : %s(%s) → %s
                        Throws : %s
                        Flags  : %s
                        """.formatted(
                        m.methodName(),
                        String.join(", ", m.parameters()),
                        m.returnType(),
                        m.thrownExceptions().isEmpty() ? "none" : String.join(", ", m.thrownExceptions()),
                        buildMethodFlags(m)))
                .collect(Collectors.joining("\n  ---\n  "));
    }

    private String buildMethodFlags(CodeMetadata.MethodInfo m) {
        List<String> flags = new ArrayList<>();
        if (m.isVoid())
            flags.add("void");
        if (m.hasConditionalLogic())
            flags.add("has-branching");
        if (m.hasLoops())
            flags.add("has-loops");
        if (m.callsExternalDependency())
            flags.add("calls-dependency");
        if (!m.thrownExceptions().isEmpty())
            flags.add("throws");
        return flags.isEmpty() ? "none" : String.join(", ", flags);
    }

    private String formatDependencies(List<CodeMetadata.FieldDependency> deps) {
        if (deps.isEmpty())
            return "  (none — no dependencies to mock)";

        return deps.stream()
                .map(d -> "  @Mock %s %s  [%s]".formatted(d.fieldType(), d.fieldName(), d.injectionType()))
                .collect(Collectors.joining("\n"));
    }
}
