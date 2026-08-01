package com.agent.web.config;

import com.agent.core.engine.AgentRunService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 在应用就绪后恢复数据库中仍处于 RUNNING 的 Run。 */
@Component
public final class RunRecoveryListener {

    private final AgentRunService runService;

    /** 创建启动恢复 listener。 */
    public RunRecoveryListener(AgentRunService runService) {
        this.runService = Objects.requireNonNull(runService, "runService 不能为空");
    }

    /** 将应用就绪事件转发为一次 Run 恢复扫描。 */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        runService.recoverRunningRuns();
    }
}
