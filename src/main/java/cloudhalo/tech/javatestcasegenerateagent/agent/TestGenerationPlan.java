package cloudhalo.tech.javatestcasegenerateagent.agent;

import cloudhalo.tech.javatestcasegenerateagent.analyzer.CodeMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Handoff record between Chain Workflow links.
 *
 * Produced by: RoutingWorkflow (Link 1)
 * Consumed by: ParallelizationWorkflow (Link 2) and EvaluatorOptimizerWorkflow (Link 4)
 *
 * Encodes ALL routing decisions upfront so later links are pure executors —
 * they don't re-classify or re-decide, they just act on the plan.
 *
 * CONTEXT ENGINEERING — SELECT strategy:
 * methodGroups partitions methods so each parallel LLM call gets ONLY
 * the 2-3 methods it needs, not the full class surface.
 */
public record TestGenerationPlan(

        WorkerType workerType,              // which specialist handles this class
        CodeMetadata metadata,              // full JavaParser analysis
        List<MethodGroup> methodGroups,     // partitioned for parallelization
        String frameworkDirective,          // injected into worker system prompt
        String testSliceAnnotation,         // @WebMvcTest / Mockito / @DataJpaTest
        String routerNotes                  // why this route was chosen (for logging)

) {

    public enum WorkerType {
        SERVICE_WORKER,
        REST_CONTROLLER_WORKER,
        REPOSITORY_WORKER,
        UTILITY_WORKER
    }

    /**
     * A batch of related methods to test together in one LLM call.
     * The unit of parallelization — each group = one concurrent ChatClient call.
     */
    public record MethodGroup(
            String groupName,
            List<CodeMetadata.MethodInfo> methods,
            List<String> sharedDependencyTypes   // types available as @Mock in this group
    ) {}

    /**
     * Partition public methods into groups for parallel generation.
     *
     * Strategy:
     *  - <= 3 methods: single group (parallelization overhead not worth it)
     *  - > 3 methods: batches of 3 (caps context per call, enables concurrency)
     *
     * Each group carries the shared dependency types so the worker knows
     * what's available to mock — without needing the full CodeMetadata.
     */
    public static List<MethodGroup> partition(CodeMetadata metadata) {
        List<CodeMetadata.MethodInfo> methods = metadata.publicMethods();
        List<String> sharedDeps = metadata.injectedFields().stream()
                .map(CodeMetadata.FieldDependency::fieldType)
                .toList();

        if (methods.size() <= 3) {
            return List.of(new MethodGroup("all-methods", methods, sharedDeps));
        }

        List<MethodGroup> groups = new ArrayList<>();
        for (int i = 0; i < methods.size(); i += 3) {
            List<CodeMetadata.MethodInfo> batch = methods.subList(i, Math.min(i + 3, methods.size()));
            groups.add(new MethodGroup("batch-" + (i / 3 + 1), batch, sharedDeps));
        }
        return groups;
    }
}