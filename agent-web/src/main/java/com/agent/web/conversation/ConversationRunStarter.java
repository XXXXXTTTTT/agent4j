package com.agent.web.conversation;

import com.agent.core.engine.AgentState;
import com.agent.core.engine.RunCheckpoint;

import java.util.function.Consumer;

/** 会话服务启动独立 Agent Run 的窄端口。 */
@FunctionalInterface
public interface ConversationRunStarter {

    /** 创建并异步启动指定图。 */
    RunCheckpoint start(
            String graphId,
            AgentState initialState,
            Consumer<RunCheckpoint> beforeDispatch);
}
