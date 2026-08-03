package com.agent.rag.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Bm25ScorerTest {

    private final Bm25Scorer scorer = new Bm25Scorer();

    @Test
    void computesStandardBm25WithFixedParameters() {
        double score = scorer.score(
                "alpha beta",
                "alpha alpha beta",
                10,
                4,
                Map.of("alpha", 2L, "beta", 5L));

        assertThat(score).isCloseTo(2.9633939806227,
                org.assertj.core.data.Offset.offset(0.000000000001));
    }

    @Test
    void tokenizesLowercaseUnicodeLetterAndDigitRuns() {
        assertThat(Bm25Scorer.tokenize("Hello, 世界 123 世界"))
                .containsExactly("hello", "世界", "123", "世界");
        assertThat(scorer.score(" ", "hello", 1, 1, Map.of())).isZero();
        assertThat(scorer.score("missing", "hello", 1, 1, Map.of())).isZero();
    }

    @Test
    void returnsZeroWhenCorpusStatisticsAreEmpty() {
        assertThat(scorer.score(
                "alpha", "alpha", 0, 0, Map.of("alpha", 0L))).isZero();
        assertThat(scorer.score(
                "alpha", "alpha", 10, 0, Map.of("alpha", 1L))).isZero();
    }
}
