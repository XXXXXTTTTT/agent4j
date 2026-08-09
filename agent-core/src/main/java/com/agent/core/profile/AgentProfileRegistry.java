package com.agent.core.profile;

import com.agent.core.engine.GraphRegistry;
import com.agent.core.engine.GraphTopology;
import com.agent.core.engine.StateGraph;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.Collections;

/** 管理由应用构造器注入的 Profile，并提供只读拓扑检查。 */
public final class AgentProfileRegistry {

    private final Map<String, AgentProfile> profiles;
    private final GraphRegistry graphRegistry;

    /** 创建不可变 Profile 注册表。 */
    public AgentProfileRegistry(
            Map<String, AgentProfile> profiles,
            GraphRegistry graphRegistry) {
        Objects.requireNonNull(profiles, "profiles 不能为空");
        this.graphRegistry = Objects.requireNonNull(graphRegistry, "graphRegistry 不能为空");
        Map<String, AgentProfile> checked = new java.util.LinkedHashMap<>();
        profiles.forEach((beanName, profile) -> {
            Objects.requireNonNull(beanName, "Profile Bean 名称不能为空");
            AgentProfile value = Objects.requireNonNull(profile, "profile 不能为空");
            if (checked.putIfAbsent(value.profileId(), value) != null) {
                throw new IllegalArgumentException("Profile 标识重复: " + value.profileId());
            }
        });
        this.profiles = Map.copyOf(checked);
    }

    /** 返回稳定排序的精确 Profile 标识。 */
    public Set<String> profileIds() {
        return Collections.unmodifiableSet(new TreeSet<>(profiles.keySet()));
    }

    /** 按精确标识读取 Profile。 */
    public AgentProfile get(String profileId) {
        requireProfileId(profileId);
        AgentProfile profile = profiles.get(profileId);
        if (profile == null) {
            throw new AgentProfileNotFoundException(profileId);
        }
        return profile;
    }

    /** 创建一次图并读取拓扑，读取后立即关闭图实例。 */
    public AgentProfileSnapshot inspect(String profileId) {
        AgentProfile profile = get(profileId);
        try (StateGraph graph = graphRegistry.create(profile.graphId())) {
            GraphTopology topology = graph.inspectTopology();
            return new AgentProfileSnapshot(profile, topology);
        }
    }

    private static void requireProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId 不能为空");
        }
    }
}
