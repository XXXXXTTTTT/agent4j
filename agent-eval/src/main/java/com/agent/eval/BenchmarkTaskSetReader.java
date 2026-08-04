package com.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 严格读取版本化 JSONL Benchmark 任务集。 */
public final class BenchmarkTaskSetReader {

    private static final Set<String> FIELDS = Set.of(
            "id", "category", "prompt", "successCriteria", "metadata");
    private final ObjectMapper objectMapper;

    /** 使用默认 Jackson 映射器创建读取器。 */
    public BenchmarkTaskSetReader() {
        this(new ObjectMapper());
    }

    /** 注入 Jackson 映射器。 */
    public BenchmarkTaskSetReader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 以 UTF-8 读取所有非空 JSONL 行。 */
    public BenchmarkTaskSet read(InputStream input) {
        Objects.requireNonNull(input, "input 不能为空");
        List<BenchmarkTask> tasks = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    throw new IllegalArgumentException("第 " + lineNumber + " 行为空行");
                }
                tasks.add(parseLine(line, lineNumber));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取 Benchmark JSONL 失败", exception);
        }
        Set<String> ids = new HashSet<>();
        for (BenchmarkTask task : tasks) {
            if (!ids.add(task.id())) {
                throw new IllegalArgumentException("任务 ID 必须唯一: " + task.id());
            }
        }
        return new BenchmarkTaskSet(tasks);
    }

    private BenchmarkTask parseLine(String line, int lineNumber) {
        final JsonNode node;
        try {
            node = objectMapper.readTree(line);
        } catch (IOException exception) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行不是合法 JSON", exception);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 JSON 必须是对象");
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!FIELDS.contains(field)) {
                throw new IllegalArgumentException("第 " + lineNumber + " 行包含未知字段: " + field);
            }
        }
        for (String field : FIELDS) {
            if (!node.has(field)) {
                throw new IllegalArgumentException("第 " + lineNumber + " 行缺少字段: " + field);
            }
        }
        JsonNode metadataNode = node.get("metadata");
        if (!metadataNode.isObject()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行 metadata 必须是对象");
        }
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadataNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException("第 " + lineNumber + " 行 metadata 值必须是字符串");
            }
            metadata.put(entry.getKey(), entry.getValue().textValue());
        });
        if (!node.get("id").isTextual() || !node.get("category").isTextual()
                || !node.get("prompt").isTextual() || !node.get("successCriteria").isTextual()) {
            throw new IllegalArgumentException("第 " + lineNumber + " 行文本字段必须是字符串");
        }
        return new BenchmarkTask(
                node.get("id").textValue(),
                node.get("category").textValue(),
                node.get("prompt").textValue(),
                node.get("successCriteria").textValue(),
                metadata);
    }
}
