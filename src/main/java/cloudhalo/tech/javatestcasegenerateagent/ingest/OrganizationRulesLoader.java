package cloudhalo.tech.javatestcasegenerateagent.ingest;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OrganizationRulesLoader {

    private final VectorStore vectorStore;

    @Value("classpath:rules/organization_rules.md")
    Resource rulesResource;

    @Value("${organization.ingest-data}")
    boolean loadData;

    @PostConstruct
    public void loadOrganizationRules() {
        if (loadData) {
            var markdownDocumentReaderConfig = MarkdownDocumentReaderConfig
                    .builder()
                    .withIncludeCodeBlock(true)
                    .withAdditionalMetadata("source", "organization_rules.md")
                    .withAdditionalMetadata("year", "2026")
                    .build();

            MarkdownDocumentReader markdownDocumentReader = new MarkdownDocumentReader(rulesResource, markdownDocumentReaderConfig);
            var documents = markdownDocumentReader.get();
            vectorStore.add(documents);
        }
    }

}
