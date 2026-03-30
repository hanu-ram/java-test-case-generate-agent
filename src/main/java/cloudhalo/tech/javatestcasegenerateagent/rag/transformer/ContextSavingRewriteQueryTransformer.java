package cloudhalo.tech.javatestcasegenerateagent.rag.transformer;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

public class ContextSavingRewriteQueryTransformer implements QueryTransformer {
    public static final String RE_RANK_QUERY = "rerankQuery";
    private final QueryTransformer delegate;

    public ContextSavingRewriteQueryTransformer(QueryTransformer delegate) {
        this.delegate = delegate;
    }

    @Override
    public @NonNull Query transform(Query query) {
        Query transformed = delegate.apply(query);

        query.context().put(RE_RANK_QUERY, transformed.text());

        return transformed;
    }
}
