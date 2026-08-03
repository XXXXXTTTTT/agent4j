package com.agent.rag.embedding;

/** 将文本转换为固定维度向量的注入端口。 */
public interface EmbeddingModel {

    /** 返回模型输出的固定维度。 */
    int dimensions();

    /** 将文本转换为向量。 */
    float[] embed(String text);
}
