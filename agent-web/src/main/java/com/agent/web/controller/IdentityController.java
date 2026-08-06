package com.agent.web.controller;

import com.agent.web.identity.ActorResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 返回当前请求主体的身份信息。 */
@RestController
@RequestMapping("/api/identity")
public final class IdentityController {

    private final ActorResolver actorResolver;

    public IdentityController(ActorResolver actorResolver) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
    }

    @GetMapping
    public ActorView current() {
        return ActorView.from(actorResolver.current());
    }
}
