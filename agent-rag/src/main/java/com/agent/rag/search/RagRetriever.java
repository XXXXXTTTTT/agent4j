package com.agent.rag.search;

import com.agent.rag.domain.RagHit;
import com.agent.rag.domain.RagQuery;

import java.util.List;

/** 代码库基础召回端口。 */
@FunctionalInterface
public interface RagRetriever {

    /** 返回查询对应的稳定排序命中。 */
    List<RagHit> search(RagQuery query);
}
