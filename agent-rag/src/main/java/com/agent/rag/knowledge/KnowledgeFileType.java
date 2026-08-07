package com.agent.rag.knowledge;

import java.util.Arrays;

/** 项目知识文件的精确文件名映射。 */
public enum KnowledgeFileType {
    SOUL("SOUL.md"),
    AGENTS("AGENTS.md"),
    CLAUDE("CLAUDE.md");

    private final String fileName;

    KnowledgeFileType(String fileName) {
        this.fileName = fileName;
    }

    /** 返回知识文件的精确名称。 */
    public String fileName() {
        return fileName;
    }

    /** 仅按大小写完全一致的文件名映射类型。 */
    public static KnowledgeFileType fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.fileName.equals(fileName))
                .findFirst()
                .orElse(null);
    }
}
