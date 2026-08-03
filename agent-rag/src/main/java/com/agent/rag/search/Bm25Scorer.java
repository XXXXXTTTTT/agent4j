package com.agent.rag.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 使用固定参数计算 BM25 文本相关性。 */
public final class Bm25Scorer {

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");

    /** 返回查询文本的 lowercase Unicode 字母/数字 token。 */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return List.copyOf(tokens);
    }

    /** 计算一篇文档相对于查询的 BM25 分数。 */
    public double score(
            String query,
            String content,
            long corpusSize,
            double averageDocumentLength,
            Map<String, Long> documentFrequencies) {
        if (corpusSize <= 0 || averageDocumentLength <= 0
                || content == null || content.isBlank()) {
            return 0;
        }
        List<String> queryTerms = new ArrayList<>(
                new LinkedHashSet<>(tokenize(query)));
        if (queryTerms.isEmpty()) {
            return 0;
        }
        Map<String, Integer> termFrequency = new HashMap<>();
        for (String token : tokenize(content)) {
            termFrequency.merge(token, 1, Integer::sum);
        }
        double documentLength = termFrequency.values().stream()
                .mapToInt(Integer::intValue)
                .sum();
        double normalization = K1 * (1 - B + B * documentLength / averageDocumentLength);
        double score = 0;
        for (String term : queryTerms) {
            int frequency = termFrequency.getOrDefault(term, 0);
            if (frequency == 0) {
                continue;
            }
            long documentFrequency = documentFrequencies == null
                    ? 0
                    : documentFrequencies.getOrDefault(term, 0L);
            documentFrequency = Math.max(0, Math.min(corpusSize, documentFrequency));
            if (documentFrequency == 0) {
                continue;
            }
            double idf = Math.log(1 +
                    (corpusSize - documentFrequency + 0.5)
                            / (documentFrequency + 0.5));
            score += idf * (frequency * (K1 + 1))
                    / (frequency + normalization);
        }
        return Double.isFinite(score) && score >= 0 ? score : 0;
    }
}
