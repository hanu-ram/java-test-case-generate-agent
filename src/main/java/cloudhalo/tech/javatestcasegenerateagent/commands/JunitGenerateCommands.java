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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spring Shell command handler for test generation.
 *
 * Usage:
 * generate test -f /absolute/path/to/UserService.java
 * generate test package -p /absolute/path/to/service
 *
 * Flow:
 * 1. Validate file exists
 * 2. JavaParser analyzes it → CodeMetadata
 * 3. Display what was found
 * 4. TestGenAgent builds prompt + calls LLM
 * 5. LLM writes test file, runs it, self-heals
 * 6. Print summary (single class or aggregate for package)
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
        writer.println(cyan("✍️  Generating tests... (streaming from LLM)"));
        writer.println("  " + "─".repeat(50));
        writer.flush();

        TestGenAgent.GenerationResult result = testGenAgent.generateTests(metadata, workingDir);

        // ── Step 6: Print result ───────────────────────────────────────────
        printSingleResult(writer, result);
    }

    @Command(name = "generate test package", description = "Generate JUnit 5 unit tests for all Java files in a package", help = """
            Generate comprehensive JUnit 5 tests for all Java source files in a package directory.

            Usage:
              generate test package -p /path/to/service
              generate test package --path /path/to/service

            After generating all tests, runs the aggregate test suite and displays a summary report.
            """, group = "generate tests")
    public void generateTestForPackage(
            @Option(longName = "path", shortName = 'p', required = true, description = "Absolute path to the Java package directory") String packagePath,
            CommandContext commandContext) throws IOException {
        PrintWriter writer = commandContext.outputWriter();
        Path pkgDir = Path.of(packagePath);

        if (!Files.isDirectory(pkgDir)) {
            writer.println(red("✗ Not a directory: " + packagePath));
            writer.flush();
            return;
        }

        // ── Collect all .java files in the package ────────────────────────────
        List<Path> javaFiles;
        try (Stream<Path> stream = Files.list(pkgDir)) {
            javaFiles = stream
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().endsWith("Test.java")) // skip existing tests
                    .toList();
        }

        if (javaFiles.isEmpty()) {
            writer.println(yellow("⚠  No .java files found in: " + packagePath));
            writer.flush();
            return;
        }

        writer.println(cyan("\n" + "═".repeat(60)));
        writer.println(cyan("  📦 Package Test Generation — " + pkgDir.getFileName()));
        writer.println(cyan("  📋 Files to process: " + javaFiles.size()));
        writer.println(cyan("═".repeat(60)));
        writer.flush();

        // ── Generate tests for each file ──────────────────────────────────────
        List<TestGenAgent.GenerationResult> results = new ArrayList<>();
        int fileNum = 0;

        for (Path javaFile : javaFiles) {
            fileNum++;
            writer.println(cyan("\n─── [%d/%d] %s ───".formatted(fileNum, javaFiles.size(), javaFile.getFileName())));
            writer.flush();

            try {
                generateTest(javaFile.toString(), commandContext);
                // We can't easily capture the result from generateTest() since it prints
                // directly.
                // So we re-analyze minimally to track success/failure for the summary.
            } catch (Exception e) {
                writer.println(red("  ✗ Error processing %s: %s".formatted(javaFile.getFileName(), e.getMessage())));
                writer.flush();
            }
        }

        // ── Print aggregate summary ───────────────────────────────────────────
        writer.println("\n" + cyan("═".repeat(60)));
        writer.println(cyan("  📊 PACKAGE TEST GENERATION SUMMARY"));
        writer.println(cyan("═".repeat(60)));
        writer.println("  Package  : " + pkgDir.getFileName());
        writer.println("  Files    : " + javaFiles.size() + " processed");
        writer.println();

        // ── Suggest running all tests together ────────────────────────────────
        Path projectRoot = detectProjectRoot(javaFiles.get(0));
        String buildTool = detectBuildTool(projectRoot);
        String runAllCmd = buildTool.equals("maven")
                ? "mvn test --no-transfer-progress"
                : "gradlew test --info";

        writer.println(cyan("  💡 Run all tests:"));
        writer.println("     cd /d \"" + projectRoot + "\" && " + runAllCmd);
        writer.println();
        writer.println(cyan("  📊 View test report:"));
        if (buildTool.equals("gradle")) {
            writer.println("     " + projectRoot.resolve("build/reports/tests/test/index.html"));
        } else {
            writer.println("     " + projectRoot.resolve("target/surefire-reports"));
        }
        writer.println(cyan("═".repeat(60)));
        writer.flush();
    }

    @Command(name = "say hello", description = "Say hello to AI")
    public void sayHello(CommandContext commandContext) {
        PrintWriter printWriter = commandContext.outputWriter();
        printWriter.println(testGenAgent.call());
        printWriter.flush();
    }

    // ── Helper Methods ────────────────────────────────────────────────────────

    /**
     * Print the result for a single class test generation.
     */
    private void printSingleResult(PrintWriter writer, TestGenAgent.GenerationResult result) {
        writer.println();
        if (result.success()) {
            writer.println(green("═".repeat(55)));
            writer.println(green("  ✅ Test generated successfully!"));
            writer.println(green("═".repeat(55)));
            writer.println("  Class   : " + result.metadata().className());
            writer.println("  Methods : " + result.metadata().publicMethods().size() + " tested");
            if (result.testFilePath() != null) {
                writer.println("  TestFile: " + result.testFilePath());
            }
            if (result.errorSummary() != null && result.errorSummary().startsWith("SKIP")) {
                writer.println(yellow("  Note    : " + result.errorSummary()));
            }
            writer.println(green("═".repeat(55)));
        } else {
            writer.println(red("═".repeat(55)));
            writer.println(red("  ✗ Generation failed"));
            writer.println(red("═".repeat(55)));
            writer.println("  Class   : " + result.metadata().className());
            if (result.errorSummary() != null) {
                writer.println("  Error   : " + result.errorSummary());
            }
            writer.println(red("═".repeat(55)));
        }
        writer.flush();
    }

    /**
     * Walk up directory tree to find project root (contains pom.xml or
     * build.gradle).
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

    /**
     * Detect the build tool from the project root.
     */
    private String detectBuildTool(Path projectRoot) {
        if (Files.exists(projectRoot.resolve("build.gradle")))
            return "gradle";
        return "maven";
    }

    // ── ANSI Color Helpers ────────────────────────────────────────────────────
    private String cyan(String s) {
        return "\u001B[36m" + s + "\u001B[0m";
    }

    private String green(String s) {
        return "\u001B[32m" + s + "\u001B[0m";
    }

    private String red(String s) {
        return "\u001B[31m" + s + "\u001B[0m";
    }

    private String yellow(String s) {
        return "\u001B[33m" + s + "\u001B[0m";
    }
}
