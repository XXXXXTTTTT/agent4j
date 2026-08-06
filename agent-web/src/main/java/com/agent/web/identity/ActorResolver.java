package com.agent.web.identity;

/** 解析当前调用者的身份边界，可替换为网关或 Spring Security 实现。 */
@FunctionalInterface
public interface ActorResolver {

    /** 返回当前请求主体。 */
    Actor current();
}
