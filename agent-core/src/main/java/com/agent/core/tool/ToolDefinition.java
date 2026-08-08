package com.agent.core.tool;

import com.agent.core.intent.RequiredCapability;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 可注册工具的不可变定义。 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        Set<RequiredCapability> requiredCapabilities,
        ToolRiskLevel riskLevel,
        Duration timeout,
        ToolHandler handler) {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /** 校验定义并复制所有可变输入。 */
    public ToolDefinition {
        requireName(name);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        if (description.codePointCount(0, description.length()) > 4_000) {
            throw new IllegalArgumentException("description 不能超过 4000 个 code point");
        }
        Objects.requireNonNull(inputSchema, "inputSchema 不能为空");
        if (!inputSchema.isObject()) {
            throw new IllegalArgumentException("inputSchema 必须是 JSON object");
        }
        inputSchema = inputSchema.deepCopy();
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities 不能为空"));
        Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        Objects.requireNonNull(timeout, "timeout 不能为空");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout 必须大于 0 且不超过 10 分钟");
        }
        Objects.requireNonNull(handler, "handler 不能为空");
    }

    /** 返回 Schema 的独立副本。 */
    @Override
    public JsonNode inputSchema() {
        return inputSchema.deepCopy();
    }

    static void requireName(String value) {
        if (value == null || !NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("name 格式不合法");
        }
    }
}
