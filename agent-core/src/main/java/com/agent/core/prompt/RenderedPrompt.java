package com.agent.core.prompt;

import java.util.Objects;

/** 已完成变量渲染、可用于审计的 Prompt。 */
public record RenderedPrompt(
        String name,
        String version,
        String staticSection,
        String dynamicSection,
        String fingerprint) {

    /** 校验渲染结果。 */
    public RenderedPrompt {
        Objects.requireNonNull(name, "name 不能为空");
        Objects.requireNonNull(version, "version 不能为空");
        Objects.requireNonNull(staticSection, "staticSection 不能为空");
        Objects.requireNonNull(dynamicSection, "dynamicSection 不能为空");
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fingerprint 必须是 64 位小写 SHA-256");
        }
    }

    /** 合并静态与动态分区。 */
    public String combined() {
        return staticSection + "\n\n" + dynamicSection;
    }
}
