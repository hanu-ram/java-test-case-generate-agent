package cloudhalo.tech.javatestcasegenerateagent.rag.reranker;

import cloudhalo.tech.javatestcasegenerateagent.rag.transformer.ContextSavingRewriteQueryTransformer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class NvidiaRerankingDocumentPostProcessor implements DocumentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(NvidiaRerankingDocumentPostProcessor.class);

    private final NvidiaRerankerProperties properties;
    private final WebClient webClient;

    public NvidiaRerankingDocumentPostProcessor(WebClient.Builder webClientBuilder,
                                                NvidiaRerankerProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public @NonNull List<Document> process(Query query, List<Document> documents) {
        if (!properties.isEnabled() || documents.isEmpty() || documents.size() == 1) {
            return documents;
        }

        List<Document> candidates = documents.stream()
                .limit(Math.max(properties.getCandidateCount(), properties.getTopK()))
                .toList();

        try {
            NvidiaRerankingResponse response = webClient.post()
                    .uri(properties.getPath())
                    .bodyValue(buildRequestBody(query, candidates))
                    .retrieve()
                    .bodyToMono(NvidiaRerankingResponse.class)
                    .block(properties.getTimeout());

            List<RankedDocument> rankedDocuments = rankDocuments(candidates, response);
            if (rankedDocuments.isEmpty()) {
                return handleFailure("NVIDIA reranker returned no usable rankings", null, documents);
            }

            return rankedDocuments.stream()
                    .sorted(Comparator.comparing(RankedDocument::score).reversed())
                    .limit(Math.min(properties.getTopK(), rankedDocuments.size()))
                    .map(RankedDocument::document)
                    .toList();
        }
        catch (Exception ex) {
            return handleFailure("NVIDIA reranker call failed", ex, documents);
        }
    }

    private NvidiaRerankingRequest buildRequestBody(Query query, List<Document> candidates) {
        List<NvidiaRerankingRequest.Passage> passages = candidates.stream()
                .map(this::passageText)
                .map(text -> StringUtils.hasText(text) ? text : " ")
                .map(NvidiaRerankingRequest.Passage::new)
                .toList();

        return new NvidiaRerankingRequest(
                properties.getModel(),
                new NvidiaRerankingRequest.Query((String) query.context().get(ContextSavingRewriteQueryTransformer.RE_RANK_QUERY)),
                passages
        );
    }

    private List<RankedDocument> rankDocuments(List<Document> candidates, NvidiaRerankingResponse response) {
        Map<Integer, Double> scoresByIndex = extractScores(response, candidates.size());
        if (scoresByIndex.isEmpty()) {
            return List.of();
        }

        List<RankedDocument> rankedDocuments = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Double rerankScore = scoresByIndex.get(i);
            if (rerankScore == null) {
                continue;
            }

            Document rankedDocument = withRerankScore(candidates.get(i), rerankScore);
            rankedDocuments.add(new RankedDocument(rankedDocument, rerankScore));
        }
        return rankedDocuments;
    }

    private Map<Integer, Double> extractScores(NvidiaRerankingResponse response, int expectedCount) {
        if (response == null || response.rankings() == null || response.rankings().isEmpty()) {
            return Map.of();
        }

        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (NvidiaRerankingResponse.Ranking ranking : response.rankings()) {
            if (ranking == null) {
                continue;
            }

            int index = ranking.index();
            if (index < 0 || index >= expectedCount) {
                continue;
            }

            scores.put(index, ranking.logit());
        }
        return scores;
    }

    private Document withRerankScore(Document document, double rerankScore) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        if (document.getScore() != null) {
            metadata.put("vectorScore", document.getScore());
        }
        metadata.put("rerankScore", rerankScore);

        return document.mutate()
                .metadata(metadata)
                .score(rerankScore)
                .build();
    }

    private String passageText(Document document) {
        String text = document.getText();
        if (StringUtils.hasText(text)) {
            return text;
        }
        return document.getFormattedContent(MetadataMode.NONE);
    }

    private List<Document> handleFailure(String message, Exception ex, List<Document> documents) {
        if (properties.isFailOpen()) {
            if (ex == null) {
                log.warn("{}.", message);
            }
            else {
                log.warn("{}: {}", message, ex.getMessage());
            }
            return documents.stream()
                    .limit(Math.min(properties.getTopK(), documents.size()))
                    .toList();
        }

        if (ex instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException(message, ex);
    }

    private record RankedDocument(Document document, double score) {
        private RankedDocument {
            Objects.requireNonNull(document, "document cannot be null");
        }
    }
}
