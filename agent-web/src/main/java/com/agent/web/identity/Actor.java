package com.agent.web.identity;

import java.util.Objects;

/** 当前请求经过身份解析后的不可变主体。 */
public record Actor(String userId, String displayName) {

    /** 校验用户标识和显示名称。 */
    public Actor {
        requireText(userId, "userId");
        requireText(displayName, "displayName");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name + " 不能为空").isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空白");
        }
    }
}
