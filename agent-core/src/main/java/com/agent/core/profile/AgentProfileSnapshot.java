package com.agent.core.profile;

import com.agent.core.engine.GraphTopology;

import java.util.Objects;

/** Profile 声明与只读图拓扑的不可变快照。 */
public record AgentProfileSnapshot(AgentProfile profile, GraphTopology topology) {

    /** 校验快照组成部分。 */
    public AgentProfileSnapshot {
        Objects.requireNonNull(profile, "profile 不能为空");
        Objects.requireNonNull(topology, "topology 不能为空");
    }
}
