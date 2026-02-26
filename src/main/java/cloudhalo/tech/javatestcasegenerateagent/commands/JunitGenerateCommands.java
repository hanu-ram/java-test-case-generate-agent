package cloudhalo.tech.javatestcasegenerateagent.commands;

import cloudhalo.tech.javatestcasegenerateagent.agent.TestGenAgent;
import cloudhalo.tech.javatestcasegenerateagent.analyzer.CodeMetadata;
import cloudhalo.tech.javatestcasegenerateagent.analyzer.JavaCodeAnalyzer;
import lombok.RequiredArgsConstructor;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring Shell command handler for test generation.
 *
 * Usage:
 *   generate test -f /absolute/path/to/UserService.java
 *   generate test --file /absolute/path/to/UserService.java
 *
 * Flow:
 *   1. Validate file exists
 *   2. JavaParser analyzes it → CodeMetadata
 *   3. Display what was found
 *   4. TestGenAgent builds prompt + calls Claude
 *   5. TestFileWriter saves the result
 *   6. Print summary
 */
@Component
@RequiredArgsConstructor
public class JunitGenerateCommands {

    private final JavaCodeAnalyzer codeAnalyzer;
    private final TestGenAgent testGenAgent;

    @Command(
            name = "generate test",
            alias = {"generate-junit", "gen test"},
            description = "Generate JUnit 5 unit tests for a Java class",
            help = """
                   Generate comprehensive JUnit 5 tests for a Java source file.
                   
                   Usage:
                     generate test -f /path/to/MyService.java
                     generate test --file /path/to/MyService.java
                   
                   The agent will:
                     1. Parse the class with JavaParser (AST analysis)
                     2. Identify all testable methods and dependencies
                     3. Generate test scenarios (happy path, edge cases, exceptions)
                     4. Write a complete JUnit 5 test class to src/test/java
                   """
    )
    public void generateTest(
            @Option(
                    longName = "file",
                    shortName = 'f',
                    required = true,
                    description = "Absolute path to the Java source file"
            ) String filePath,
            CommandContext commandContext
    ) {
        PrintWriter writer = commandContext.outputWriter();

            // ── Step 1: Validate file ──────────────────────────────────────────
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                writer.println(red("✗ File not found: " + filePath));
                writer.println(yellow("  Tip: Provide the absolute path to a .java file"));
                writer.flush();
                return;
            }

            if (!filePath.endsWith(".java")) {
                writer.println(red("✗ Not a Java file: " + filePath));
                writer.flush();
                return;
            }

            // ── Step 2: Parse with JavaParser ──────────────────────────────────
            writer.println(cyan("\n🔍 Analyzing: " + path.getFileName()));
            writer.flush();

            CodeMetadata metadata;
            try {
                metadata = codeAnalyzer.analyze(filePath);
            } catch (IOException e) {
                writer.println(red("✗ Could not read file: " + e.getMessage()));
                writer.flush();
                return;
            } catch (IllegalArgumentException e) {
                writer.println(red("✗ Parse error: " + e.getMessage()));
                writer.flush();
                return;
            }

            // ── Step 3: Show what was found ────────────────────────────────────
            writer.println(green("✅ Parsed successfully"));
            writer.println(cyan("\n  CLASS ANALYSIS"));
            writer.println("  " + "─".repeat(50));
            writer.println(metadata.toDisplaySummary().lines()
                    .map(l -> "  " + l)
                    .reduce("", (a, b) -> a + b + "\n"));

            writer.println(cyan("  PUBLIC METHODS FOUND (" + metadata.publicMethods().size() + ")"));
            metadata.publicMethods().forEach(m ->
                    writer.println("  ├─ " + m.methodName() + "(" +
                            String.join(", ", m.parameters()) + ") → " + m.returnType() +
                            (m.thrownExceptions().isEmpty() ? "" : " throws " + m.thrownExceptions()))
            );

            if (!metadata.injectedFields().isEmpty()) {
                writer.println(cyan("\n  DEPENDENCIES TO MOCK (" + metadata.injectedFields().size() + ")"));
                metadata.injectedFields().forEach(d ->
                        writer.println("  ├─ @Mock " + d.fieldType() + " " + d.fieldName())
                );
            }

            writer.println();
            writer.flush();

            // ── Step 4: Detect project root ────────────────────────────────────
            Path workingDir = detectProjectRoot(path);

            // ── Step 5: Generate tests ─────────────────────────────────────────
            writer.println(cyan("✍️  Generating tests... (streaming from Claude)"));
            writer.println("  " + "─".repeat(50));
            writer.flush();

            TestGenAgent.GenerationResult result = testGenAgent.generateTests(metadata, workingDir);

            // ── Step 6: Print result ───────────────────────────────────────────
            writer.println();
            if (result.success()) {
                writer.println(green("═".repeat(55)));
                writer.println(green("  ✅ Test generated successfully!"));
                writer.println(green("═".repeat(55)));
                writer.println("  Class   : " + result.metadata().className());
                writer.println("  Methods : " + result.metadata().publicMethods().size() + " tested");
                writer.println(green("═".repeat(55)));
            } else {
                writer.println(red("✗ Generation failed"));
            }
            writer.flush();
    }
    @Command(name = "say hello", description = "Say hello to AI")
    public void sayHello(CommandContext commandContext) {
        PrintWriter printWriter = commandContext.outputWriter();
        printWriter.println(testGenAgent.call());
        printWriter.flush();
    }

    /**
     * Walk up directory tree to find project root (contains pom.xml or build.gradle).
     */
    private Path detectProjectRoot(Path sourceFile) {
        Path dir = sourceFile.getParent();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) ||
                    Files.exists(dir.resolve("build.gradle")) ||
                    Files.exists(dir.resolve("build.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return sourceFile.getParent(); // fallback
    }

    // ── ANSI Color Helpers ────────────────────────────────────────────────────
    private String cyan(String s)   { return "\u001B[36m" + s + "\u001B[0m"; }
    private String green(String s)  { return "\u001B[32m" + s + "\u001B[0m"; }
    private String red(String s)    { return "\u001B[31m" + s + "\u001B[0m"; }
    private String yellow(String s) { return "\u001B[33m" + s + "\u001B[0m"; }
}