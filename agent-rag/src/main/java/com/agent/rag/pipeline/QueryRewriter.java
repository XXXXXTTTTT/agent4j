package com.agent.rag.pipeline;

import java.util.List;

/** 将原始问题改写为额外检索查询的注入端口。 */
@FunctionalInterface
public interface QueryRewriter {

    /** 返回最多 limit 条额外查询。 */
    List<String> rewrite(String query, int limit);
}
