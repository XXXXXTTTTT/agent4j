package com.agent.rag.knowledge;

/** 项目知识读取或编译失败。 */
public class ProjectKnowledgeException extends RuntimeException {

    /** 创建带消息的知识异常。 */
    public ProjectKnowledgeException(String message) {
        super(message);
    }

    /** 创建保留根因的知识异常。 */
    public ProjectKnowledgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
