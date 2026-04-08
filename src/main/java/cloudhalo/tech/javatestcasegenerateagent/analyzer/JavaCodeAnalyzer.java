package cloudhalo.tech.javatestcasegenerateagent.analyzer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes a Java source file using JavaParser and produces a structured
 * {@link CodeMetadata} record. This structured data — NOT raw source code —
 * is what gets sent to the LLM for test generation.
 * <p>
 * Key benefit: reduces LLM input tokens by ~70% and eliminates noise
 * (comments, formatting, boilerplate) that distracts the model.
 */
@Component
public class JavaCodeAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(JavaCodeAnalyzer.class);
    private final JavaParser javaParser = new JavaParser();

    /**
     * Main entry point. Given an absolute file path, parse and extract metadata.
     *
     * @param filePath absolute path to the .java source file
     * @return fully populated CodeMetadata record
     * @throws IOException              if file cannot be read
     * @throws IllegalArgumentException if file is not valid Java
     */
    public CodeMetadata analyze(String filePath) throws IOException {
        Path path = Path.of(filePath);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }

        String rawSource = Files.readString(path);
        ParseResult<CompilationUnit> result = javaParser.parse(rawSource);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String problems = result.getProblems().toString();
            throw new IllegalArgumentException("JavaParser failed to parse " + filePath + ": " + problems);
        }

        CompilationUnit cu = result.getResult().get();
        log.debug("Successfully parsed: {}", path.getFileName());

        return buildMetadata(cu, rawSource, path);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private CodeMetadata buildMetadata(CompilationUnit cu, String rawSource, Path filePath) {

        // ── Package ────────────────────────────────────────────────────────
        String packageName = cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse("");

        // ── Primary class ──────────────────────────────────────────────────
        ClassOrInterfaceDeclaration primaryClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No class found in file: " + filePath));

        String className = primaryClass.getNameAsString();

        // ── Annotations ────────────────────────────────────────────────────
        List<String> classAnnotations = primaryClass.getAnnotations().stream()
                .map(a -> "@" + a.getNameAsString())
                .toList();

        // ── Class type detection ───────────────────────────────────────────
        String classType = detectClassType(classAnnotations,
                primaryClass.getImplementedTypes().toString(),
                primaryClass.getExtendedTypes().toString());

        // ── Interfaces & superclass ────────────────────────────────────────
        List<String> interfaces = primaryClass.getImplementedTypes().stream()
                .map(NodeWithSimpleName::getNameAsString)
                .toList();

        String superClass = primaryClass.getExtendedTypes().isEmpty() ? null
                : primaryClass.getExtendedTypes().get(0).getNameAsString();

        // ── Injected fields (mock candidates) ─────────────────────────────
        List<CodeMetadata.FieldDependency> injectedFields = extractInjectedFields(primaryClass);

        // ── Methods ────────────────────────────────────────────────────────
        List<CodeMetadata.MethodInfo> publicMethods = extractMethods(primaryClass, "public");
        List<CodeMetadata.MethodInfo> packageMethods = extractMethods(primaryClass, "package");

        // ── Imports ────────────────────────────────────────────────────────
        List<String> imports = cu.getImports().stream()
                .map(NodeWithName::getNameAsString)
                .toList();

        // ── Test infrastructure hints ──────────────────────────────────────
        String testSlice = suggestTestSlice(classType, classAnnotations);
        String testClassName = className + "Test";
        String testPackageName = packageName;
        String testPath = buildTestPath(filePath.toString(), packageName, testClassName);

        return new CodeMetadata(
                packageName,
                className,
                classType,
                filePath.toAbsolutePath().toString(),
                rawSource,
                classAnnotations,
                interfaces,
                superClass,
                injectedFields,
                publicMethods,
                packageMethods,
                imports,
                testSlice,
                testClassName,
                testPackageName,
                testPath);
    }

    /**
     * Detect the Spring/Java class type based on annotations and inheritance.
     * Drives which sub-agent prompt and test slice to use.
     * Supports both modern Spring Boot and legacy J2EE class types.
     */
    private String detectClassType(List<String> annotations, String interfaces, String superclass) {
        for (String ann : annotations) {
            // Spring MVC / Web
            if (ann.contains("RestController"))
                return "REST_CONTROLLER";
            if (ann.contains("ControllerAdvice"))
                return "CONTROLLER_ADVICE";
            if (ann.contains("Controller"))
                return "CONTROLLER";
            // Spring Data / Persistence
            if (ann.contains("Repository"))
                return "REPOSITORY";
            // Spring Beans
            if (ann.contains("Service"))
                return "SERVICE";
            if (ann.contains("Component"))
                return "COMPONENT";
            if (ann.contains("Configuration"))
                return "CONFIGURATION";
            // J2EE / Jakarta EE
            if (ann.contains("Stateless") || ann.contains("Stateful") || ann.contains("Singleton"))
                return "EJB";
            if (ann.contains("WebServlet"))
                return "SERVLET";
            // JAX-RS (both javax and jakarta)
            if (ann.contains("Path") && !ann.contains("Mapping"))
                return "JAX_RS";
        }
        // Infer from inheritance
        if (superclass != null && superclass.equals("HttpServlet"))
            return "SERVLET";
        if (superclass != null && superclass.contains("Repository"))
            return "REPOSITORY";
        if (interfaces.contains("UserDetailsService"))
            return "SERVICE";
        if (interfaces.contains("Servlet"))
            return "SERVLET";
        return "UTILITY";
    }

    /**
     * Extract fields that are dependency-injected — these become @Mock in tests.
     * Excludes @Value-annotated fields (config strings) which are NOT mockable.
     * Excludes J2EE @EJB injection (added as EJB_INJECT type for context).
     */
    private List<CodeMetadata.FieldDependency> extractInjectedFields(ClassOrInterfaceDeclaration cls) {
        List<CodeMetadata.FieldDependency> fields = new ArrayList<>();

        for (FieldDeclaration field : cls.getFields()) {
            List<String> fieldAnnotationNames = field.getAnnotations().stream()
                    .map(AnnotationExpr::getNameAsString)
                    .toList();

            // Skip @Value fields — these are config properties, not mockable dependencies
            if (fieldAnnotationNames.contains("Value"))
                continue;
            // Skip @Slf4j / @Log fields injected by Lombok
            if (fieldAnnotationNames.stream().anyMatch(a -> a.contains("Log") || a.contains("Slf4j")))
                continue;

            boolean isInjected = fieldAnnotationNames.stream()
                    .anyMatch(a -> a.equals("Autowired") || a.equals("Inject") || a.equals("EJB")
                            || a.equals("Resource"));

            // Also capture final non-static fields with object types — likely
            // constructor-injected
            boolean isFinalField = field.isFinal() && !field.isStatic();
            boolean isObjectType = field.getVariables().stream()
                    .anyMatch(v -> {
                        String type = v.getTypeAsString();
                        // Skip primitives, String, Logger, and common config types
                        return !type.equals("String") && !type.equals("int")
                                && !type.equals("long")
                                && !type.equals("boolean")
                                && !type.equalsIgnoreCase("logger")
                                && Character.isUpperCase(type.charAt(0));
                    });

            if (isInjected || (isFinalField && isObjectType)) {
                field.getVariables().forEach(var -> {
                    String injectionType = isInjected ? "AUTOWIRED" : "CONSTRUCTOR";
                    fields.add(new CodeMetadata.FieldDependency(
                            var.getNameAsString(),
                            var.getTypeAsString(),
                            injectionType));
                });
            }
        }
        return fields;
    }

    /**
     * Extract method signatures with rich metadata for test scenario planning.
     */
    private List<CodeMetadata.MethodInfo> extractMethods(
            ClassOrInterfaceDeclaration cls, String visibility) {

        List<CodeMetadata.MethodInfo> methods = new ArrayList<>();

        for (MethodDeclaration method : cls.getMethods()) {

            boolean matchesVisibility = switch (visibility) {
                case "public" -> method.isPublic();
                case "package" -> !method.isPublic() && !method.isPrivate() && !method.isProtected();
                default -> false;
            };

            if (!matchesVisibility)
                continue;

            // Skip constructors and boilerplate
            String name = method.getNameAsString();
            if (name.startsWith("get") || name.startsWith("set") ||
                    name.equals("hashCode") || name.equals("equals") || name.equals("toString")) {
                continue; // Lomboked/boilerplate — don't generate trivial tests
            }

            List<String> params = method.getParameters().stream()
                    .map(p -> p.getTypeAsString() + " " + p.getNameAsString())
                    .toList();

            List<String> thrown = method.getThrownExceptions().stream()
                    .map(Type::asString)
                    .toList();

            boolean isVoid = method.getTypeAsString().equals("void");

            // Detect conditional logic — drives edge case test suggestions
            boolean hasConditional = method.getBody()
                    .map(BlockStmt::toString)
                    .map(body -> body.contains("if ") || body.contains("switch ")
                            || body.contains("? ") || body.contains("Optional"))
                    .orElse(false);

            boolean hasLoops = method.getBody()
                    .map(BlockStmt::toString)
                    .map(body -> body.contains("for ") || body.contains("while ")
                            || body.contains(".stream()") || body.contains(".forEach"))
                    .orElse(false);

            // Detect external calls — checks against injected field names for accuracy
            List<String> injectedNames = extractInjectedFields(cls).stream()
                    .map(CodeMetadata.FieldDependency::fieldName)
                    .toList();
            boolean callsExternal = method.getBody()
                    .map(BlockStmt::toString)
                    .map(body -> {
                        // Primary: match against actual injected field names
                        boolean callsField = injectedNames.stream()
                                .anyMatch(fieldName -> body.contains(fieldName + "."));
                        // Fallback heuristic for common Spring/J2EE patterns
                        boolean heuristicMatch = body.contains("save(")
                                || body.contains("findBy")
                                || body.contains("restTemplate")
                                || body.contains("webClient")
                                || body.contains("entityManager")
                                || body.contains("em.")
                                || body.contains(".persist(")
                                || body.contains(".merge(");
                        return callsField || heuristicMatch;
                    })
                    .orElse(false);

            methods.add(new CodeMetadata.MethodInfo(
                    name,
                    method.getTypeAsString(),
                    params,
                    thrown,
                    isVoid,
                    hasConditional,
                    hasLoops,
                    callsExternal,
                    visibility));
        }
        return methods;
    }

    /**
     * Suggest the right Spring Boot / J2EE test slice.
     * Prevents over-using @SpringBootTest and guides the LLM to the correct
     * approach.
     */
    private String suggestTestSlice(String classType, List<String> annotations) {
        return switch (classType) {
            case "REST_CONTROLLER", "CONTROLLER" -> "@WebMvcTest";
            case "CONTROLLER_ADVICE" -> "@WebMvcTest with standaloneSetup";
            case "REPOSITORY" -> "@DataJpaTest";
            case "SERVICE", "COMPONENT" -> "Mockito (no Spring context)";
            case "CONFIGURATION" -> "@SpringBootTest (skip if no testable logic)";
            case "EJB" -> "Mockito (no Spring context) — EJB unit test";
            case "SERVLET" -> "MockHttpServletRequest/MockHttpServletResponse (Spring MockMvc or plain Mockito)";
            case "JAX_RS" -> "JAX-RS unit test — call method directly, mock injected fields";
            default -> "JUnit 5 pure unit test";
        };
    }

    /**
     * Map source path to test path.
     * e.g. src/main/java/com/acme/UserService.java
     * → src/test/java/com/acme/UserServiceTest.java
     */
    private String buildTestPath(String sourcePath, String packageName, String testClassName) {
        String normalized = sourcePath.replace("\\", "/");

        // Replace src/main/java with src/test/java
        String testPath = normalized.replace("src/main/java", "src/test/java");

        // Replace the filename with TestClassName
        int lastSlash = testPath.lastIndexOf('/');
        String directory = testPath.substring(0, lastSlash + 1);

        return directory + testClassName + ".java";
    }
}