package com.agent.core.engine;

/** 接收节点执行边界事件。 */
public interface GraphExecutionListener {

    /** 节点开始执行。 */
    void onNodeStarted(String nodeName, AgentState state);

    /** 节点执行完成且下一节点已解析。 */
    void onNodeCompleted(String nodeName, String nextNode, AgentState state);
}
