package com.agent.eval;

import java.util.Map;
import java.util.Objects;

/** 一个可重复执行的业务评测任务。 */
public record BenchmarkTask(
        String id,
        String category,
        String prompt,
        String successCriteria,
        Map<String, String> metadata) {

    public BenchmarkTask {
        id = requireText(id, "id");
        category = requireText(category, "category");
        prompt = requireText(prompt, "prompt");
        successCriteria = requireText(successCriteria, "successCriteria");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata 不能为空"));
        metadata.forEach((key, value) -> {
            requireText(key, "metadata key");
            requireText(value, "metadata value");
        });
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
