package com.agent.core.engine;

/** 接收节点执行边界事件。 */
public interface GraphExecutionListener {

    /** 节点开始执行。 */
    void onNodeStarted(String nodeName, AgentState state);

    /** 节点执行中的过程摘要；默认忽略以保持监听器兼容。 */
    default void onNodeProgress(String nodeName, String summary) {
    }

    /** 节点执行完成且下一节点已解析。 */
    void onNodeCompleted(String nodeName, String nextNode, AgentState state);
}
