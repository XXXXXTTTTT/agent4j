package com.agent.rag.pipeline;

/** 为短查询生成仅用于向量召回的假设文档。 */
@FunctionalInterface
public interface HypotheticalDocumentGenerator {

    /** 返回非空假设文档。 */
    String generate(String query);
}
