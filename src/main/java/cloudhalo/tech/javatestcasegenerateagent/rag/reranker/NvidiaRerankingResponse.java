package cloudhalo.tech.javatestcasegenerateagent.rag.reranker;

import java.util.List;

public record NvidiaRerankingResponse(
        List<Ranking> rankings,
        Usage usage
) {

    public record Ranking(int index, double logit) {
    }

    public record Usage(int prompt_tokens, int total_tokens) {
    }
}
