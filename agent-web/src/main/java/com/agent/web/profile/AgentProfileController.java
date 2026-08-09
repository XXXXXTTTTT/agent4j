package com.agent.web.profile;

import com.agent.core.engine.GraphTopology;
import com.agent.core.profile.AgentProfileRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/** 提供受控 Agent Profile 与图拓扑的只读 API。 */
@RestController
@RequestMapping("/api/agent-profiles")
public final class AgentProfileController {

    private final AgentProfileRegistry registry;

    /** 创建 Profile 查询 Controller。 */
    public AgentProfileController(AgentProfileRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
    }

    /** 返回所有已注入 Profile 的声明元数据。 */
    @GetMapping
    public List<AgentProfileView> list() {
        return registry.profileIds().stream()
                .map(registry::get)
                .map(AgentProfileView::from)
                .toList();
    }

    /** 返回指定 Profile 的元数据和只读拓扑快照。 */
    @GetMapping("/{profileId}")
    public AgentProfileDetailView get(@PathVariable String profileId) {
        requireProfileId(profileId);
        return AgentProfileDetailView.from(registry.inspect(profileId));
    }

    /** 返回指定 Profile 关联图的只读拓扑快照。 */
    @GetMapping("/{profileId}/topology")
    public GraphTopology topology(@PathVariable String profileId) {
        requireProfileId(profileId);
        return registry.inspect(profileId).topology();
    }

    private static void requireProfileId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId 不能为空");
        }
    }
}
