package com.agent.sandbox.ast;

import java.util.Objects;

/**
 * Java 类的源码信息。
 *
 * @param qualifiedName 完整限定类名
 * @param beginLine     起始行号
 * @param endLine       结束行号
 * @param source        原始源码
 */
public record ClassInfo(
        String qualifiedName,
        int beginLine,
        int endLine,
        String source) {

    /** 创建并校验类信息。 */
    public ClassInfo {
        qualifiedName = requireText(qualifiedName, "qualifiedName 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        validateRange(beginLine, endLine);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static void validateRange(int beginLine, int endLine) {
        if (beginLine <= 0 || endLine < beginLine) {
            throw new IllegalArgumentException("源码行号范围无效");
        }
    }
}
