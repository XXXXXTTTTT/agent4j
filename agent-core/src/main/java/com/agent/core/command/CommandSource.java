package com.agent.core.command;

/** 命令定义来源及其覆盖优先级。 */
public enum CommandSource {
    BUILT_IN(0),
    GLOBAL(1),
    WORKSPACE(2);

    private final int priority;

    CommandSource(int priority) {
        this.priority = priority;
    }

    int priority() {
        return priority;
    }
}
