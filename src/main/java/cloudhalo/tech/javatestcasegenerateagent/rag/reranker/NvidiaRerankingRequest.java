package cloudhalo.tech.javatestcasegenerateagent.rag.reranker;

import java.util.List;

public record NvidiaRerankingRequest(
        String model,
        Query query,
        List<Passage> passages
) {

    public record Query(String text) {
    }

    public record Passage(String text) {
    }
}
