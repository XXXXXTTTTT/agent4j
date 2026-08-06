package com.agent.web.controller;

import com.agent.web.identity.Actor;

import java.util.Objects;

/** 当前调用者的只读 HTTP 视图。 */
public record ActorView(String userId, String displayName) {

    public ActorView {
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(displayName, "displayName 不能为空");
    }

    public static ActorView from(Actor actor) {
        Objects.requireNonNull(actor, "actor 不能为空");
        return new ActorView(actor.userId(), actor.displayName());
    }
}
