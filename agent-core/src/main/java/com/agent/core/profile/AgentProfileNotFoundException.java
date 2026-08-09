package com.agent.core.profile;

/** 指定 Agent Profile 标识未注册。 */
public final class AgentProfileNotFoundException extends RuntimeException {

    private final String profileId;

    /** 创建 Profile 不存在异常。 */
    public AgentProfileNotFoundException(String profileId) {
        super("Agent Profile 未注册: " + profileId);
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId 不能为空");
        }
        this.profileId = profileId;
    }

    /** 返回未注册的精确 Profile 标识。 */
    public String profileId() {
        return profileId;
    }
}
