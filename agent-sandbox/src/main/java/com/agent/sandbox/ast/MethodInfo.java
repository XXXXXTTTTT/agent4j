package com.agent.sandbox.ast;

import java.util.Objects;

/**
 * Java 方法的源码信息。
 *
 * @param name        方法名
 * @param declaration 方法声明
 * @param beginLine   起始行号
 * @param endLine     结束行号
 * @param source      原始源码
 */
public record MethodInfo(
        String name,
        String declaration,
        int beginLine,
        int endLine,
        String source) {

    /** 创建并校验方法信息。 */
    public MethodInfo {
        name = requireText(name, "name 不能为空");
        declaration = requireText(declaration, "declaration 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        if (beginLine <= 0 || endLine < beginLine) {
            throw new IllegalArgumentException("源码行号范围无效");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
