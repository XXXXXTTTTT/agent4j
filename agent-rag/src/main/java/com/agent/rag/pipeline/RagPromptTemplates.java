package com.agent.rag.pipeline;

import com.agent.core.prompt.PromptCatalog;
import com.agent.core.prompt.PromptTemplate;

import java.util.List;
import java.util.Set;

/** RAG 模型增强阶段使用的固定版本 Prompt。 */
public final class RagPromptTemplates {

    private static final PromptCatalog CATALOG = new PromptCatalog(List.of(
            new PromptTemplate(
                    "rag.rewrite",
                    "1",
                    """
                            你负责为代码库检索生成额外查询。只能返回 JSON 字符串数组，禁止返回对象、说明文字或 Markdown fence。数组中的每项必须是非空字符串，且不得超过用户给出的数量上限。
                            """.trim(),
                    """
                            原始查询:
                            {{query}}
                            最多返回 {{limit}} 条额外查询。
                            """.trim(),
                    Set.of("query", "limit")),
            new PromptTemplate(
                    "rag.hyde",
                    "1",
                    """
                            你负责根据问题生成一段可能出现在目标代码库中的简短技术正文。只能返回非空纯文本，禁止调用工具，禁止添加说明前缀。
                            """.trim(),
                    """
                            原始查询:
                            {{query}}
                            """.trim(),
                    Set.of("query"))));

    private RagPromptTemplates() {
    }

    /** 返回包含固定名称与版本的 Prompt 目录。 */
    public static PromptCatalog catalog() {
        return CATALOG;
    }
}
