package com.agent.rag.pipeline;

import java.util.List;

/** 对已融合命中进行精排的注入端口。 */
@FunctionalInterface
public interface RagReranker {

    /** 返回最多 limit 条命中标识与精排分数。 */
    List<RerankedHit> rerank(String query, List<FusedHit> hits, int limit);
}
