package com.agent.web.controller;

import com.agent.web.identity.ActorResolver;
import com.agent.web.workspace.WorkspaceDirectoryBrowser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Objects;

/** 提供当前 Agent 挂载根内的目录浏览接口。 */
@RestController
@RequestMapping("/api/workspace-directories")
@ConditionalOnProperty(name = "agent.production.enabled", havingValue = "true")
public final class WorkspaceDirectoryController {

    private final ActorResolver actorResolver;
    private final WorkspaceDirectoryBrowser browser;

    public WorkspaceDirectoryController(
            ActorResolver actorResolver,
            WorkspaceDirectoryBrowser browser) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver 不能为空");
        this.browser = Objects.requireNonNull(browser, "browser 不能为空");
    }

    @GetMapping
    public WorkspaceDirectoryView browse(
            @RequestParam(defaultValue = "") String path) {
        actorResolver.current();
        Path requested = path.isBlank() ? browser.browseRoot() : Path.of(path);
        return WorkspaceDirectoryView.from(browser.browse(requested));
    }
}
