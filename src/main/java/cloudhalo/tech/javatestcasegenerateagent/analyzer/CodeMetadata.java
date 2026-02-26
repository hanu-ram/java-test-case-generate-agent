package cloudhalo.tech.javatestcasegenerateagent.analyzer;

import java.util.List;

/**
 * Structured representation of a Java class extracted by JavaParser.
 * This is what gets sent to the LLM — NOT raw source code.
 * Reduces tokens by ~70% vs raw source, eliminates noise.
 */
public record CodeMetadata(

        // ── Identity ──────────────────────────────────────────────────
        String packageName,           // com.acme.service
        String className,             // UserService
        String classType,             // SERVICE | CONTROLLER | REPOSITORY | UTILITY | COMPONENT
        String absolutePath,          // /home/user/project/src/main/java/...
        String rawSource,             // Full source — only sent when LLM needs exact impl detail

        // ── Spring Context ────────────────────────────────────────────
        List<String> classAnnotations,    // [@Service, @Transactional]
        List<String> classInterfaces,     // [UserDetailsService, Serializable]
        String superClass,                // BaseService or null

        // ── Dependencies (what needs to be mocked) ────────────────────
        List<FieldDependency> injectedFields,   // @Autowired / @Inject / constructor-injected

        // ── API Surface (what needs to be tested) ─────────────────────
        List<MethodInfo> publicMethods,
        List<MethodInfo> packagePrivateMethods,

        // ── Imports (for generating correct test imports) ─────────────
        List<String> imports,

        // ── Test Infrastructure Hints ─────────────────────────────────
        String suggestedTestSlice,    // @WebMvcTest | @DataJpaTest | @SpringBootTest | Mockito
        String testClassName,         // UserServiceTest
        String testPackageName,       // com.acme.service (same as source)
        String suggestedTestPath      // src/test/java/com/acme/service/UserServiceTest.java

) {

    // ── Nested Records ─────────────────────────────────────────────────

    public record FieldDependency(
            String fieldName,         // userRepository
            String fieldType,         // UserRepository
            String injectionType      // CONSTRUCTOR | AUTOWIRED | INJECT
    ) {}

    public record MethodInfo(
            String methodName,        // createUser
            String returnType,        // UserDto
            List<String> parameters,  // [CreateUserRequest request]
            List<String> thrownExceptions,  // [UserAlreadyExistsException]
            boolean isVoid,
            boolean hasConditionalLogic,  // if/else/switch present
            boolean hasLoops,
            boolean callsExternalDependency,
            String visibility         // public | protected | package-private
    ) {}

    // ── Convenience Methods ────────────────────────────────────────────

    public boolean isSpringService() {
        return classAnnotations.stream()
                .anyMatch(a -> a.contains("Service"));
    }

    public boolean isRestController() {
        return classAnnotations.stream()
                .anyMatch(a -> a.contains("RestController") || a.contains("Controller"));
    }

    public boolean isRepository() {
        return classAnnotations.stream()
                .anyMatch(a -> a.contains("Repository"));
    }

    public boolean hasExceptionHandling() {
        return publicMethods.stream()
                .anyMatch(m -> !m.thrownExceptions().isEmpty());
    }

    public boolean hasConditionalLogic() {
        return publicMethods.stream()
                .anyMatch(MethodInfo::hasConditionalLogic);
    }

    public int totalTestableMethodCount() {
        return publicMethods.size() + packagePrivateMethods.size();
    }

    /**
     * Compact summary for logging/display — not for LLM context.
     */
    public String toDisplaySummary() {
        return """
               Class    : %s (%s)
               Package  : %s
               Type     : %s
               Methods  : %d public
               Mocks    : %d dependencies
               Slice    : %s
               """.formatted(
                className, superClass != null ? "extends " + superClass : "no superclass",
                packageName, classType,
                publicMethods.size(),
                injectedFields.size(),
                suggestedTestSlice
        );
    }
}