package com.govagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.govagent.tool.mock.MockDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mock RAG：基于关键词重合度在 faq.json 里检索。
 * 真实实现：POST {rag.url}/rag/query {question, topK} → {answer, sources}。
 */
@Component
@RequiredArgsConstructor
public class MockRagClient implements RagClient {

    private final MockDataService mockDataService;

    @Override
    public RagAnswer answer(String question, int topK) {
        List<String> questionTerms = terms(question);
        List<Scored> scored = new ArrayList<>();
        for (JsonNode item : mockDataService.get("faq").get("faqs")) {
            String text = item.get("question").asText() + " " + item.get("keywords").asText();
            double score = overlap(questionTerms, terms(text));
            if (score > 0) {
                scored.add(new Scored(score, item));
            }
        }
        if (scored.isEmpty()) {
            return RagAnswer.notMatched(question);
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        JsonNode best = scored.get(0).item;
        List<RagSource> sources = new ArrayList<>();
        int limit = Math.min(topK, scored.size());
        for (int i = 0; i < limit; i++) {
            JsonNode item = scored.get(i).item;
            sources.add(new RagSource(
                    item.get("question").asText(),
                    item.get("answer").asText().length() > 80
                            ? item.get("answer").asText().substring(0, 80) + "…"
                            : item.get("answer").asText(),
                    scored.get(i).score));
        }
        return new RagAnswer(best.get("answer").asText(), sources, true);
    }

    private List<String> terms(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String token : text.toLowerCase().split("[^\\u4e00-\\u9fa5a-z0-9]+")) {
            if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private double overlap(List<String> questionTerms, List<String> docTerms) {
        long hits = questionTerms.stream().filter(docTerms::contains).count();
        return questionTerms.isEmpty() ? 0 : (double) hits / questionTerms.size();
    }

    private record Scored(double score, JsonNode item) {
    }
}
