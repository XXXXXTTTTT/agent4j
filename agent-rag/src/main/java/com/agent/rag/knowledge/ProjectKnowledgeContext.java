package com.agent.rag.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 项目知识编译结果及其可复现指纹。 */
public record ProjectKnowledgeContext(
        String prompt,
        List<KnowledgeSource> sources,
        String fingerprint,
        int estimatedTokens) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** 冻结来源并校验空上下文元数据的一致性。 */
    public ProjectKnowledgeContext {
        prompt = Objects.requireNonNull(prompt, "prompt 不能为空");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources 不能为空"));
        if (sources.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("sources 不能包含 null");
        }
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint 不能为空");
        if (!fingerprint.isEmpty() && !SHA256.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("fingerprint 必须是 64 位小写十六进制字符串");
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens 不能为负数");
        }
        if (sources.isEmpty()) {
            if (!prompt.isEmpty() || !fingerprint.isEmpty() || estimatedTokens != 0) {
                throw new IllegalArgumentException("空 sources 必须对应空 prompt、fingerprint 和 token");
            }
        } else if (prompt.isBlank() || fingerprint.isEmpty()) {
            throw new IllegalArgumentException("非空 sources 必须包含 prompt 和 fingerprint");
        }
    }

    /** 返回确定性的空项目知识上下文。 */
    public static ProjectKnowledgeContext empty() {
        return EmptyHolder.INSTANCE;
    }

    private static final class EmptyHolder {
        private static final ProjectKnowledgeContext INSTANCE =
                new ProjectKnowledgeContext("", List.of(), "", 0);
    }
}
