package com.agent.web.identity;

/** 单机部署使用配置用户的身份解析器。 */
public final class ConfiguredActorResolver implements ActorResolver {

    private final Actor actor;

    /** 创建固定配置主体。 */
    public ConfiguredActorResolver(String userId, String displayName) {
        this.actor = new Actor(userId, displayName);
    }

    /** 返回固定配置主体。 */
    @Override
    public Actor current() {
        return actor;
    }
}
