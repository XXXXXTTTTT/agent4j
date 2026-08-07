package com.agent.core.harness;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

/** 按注册顺序发布事件并隔离非关键 Hook 故障。 */
public final class HarnessHookChain {

    private static final Logger LOGGER = System.getLogger(HarnessHookChain.class.getName());

    private final List<HarnessHook> hooks;
    private final HarnessAuditSink auditSink;

    /** 创建使用默认审计端口的 Hook 链。 */
    public HarnessHookChain(List<HarnessHook> hooks) {
        this(hooks, HarnessAuditSink.noop());
    }

    /** 创建带审计端口的 Hook 链。 */
    public HarnessHookChain(List<HarnessHook> hooks, HarnessAuditSink auditSink) {
        this.hooks = List.copyOf(Objects.requireNonNull(hooks, "hooks 不能为空"));
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink 不能为空");
    }

    /** 返回不会被调用方修改的注册顺序。 */
    public List<HarnessHook> hooks() {
        return hooks;
    }

    /** 按固定顺序向全部 Hook 发布事件。 */
    public void publish(HarnessEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        for (HarnessHook hook : hooks) {
            try {
                hook.onEvent(event);
            } catch (RuntimeException cause) {
                HarnessHookException failure = new HarnessHookException(
                        hook.getClass().getName(), event.eventType(), cause);
                if (hook.critical()) {
                    throw failure;
                }
                audit(failure);
            }
        }
    }

    /** 返回没有 Hook 的链。 */
    public static HarnessHookChain noop() {
        return new HarnessHookChain(List.of());
    }

    private void audit(HarnessHookException failure) {
        try {
            auditSink.record(failure);
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
            LOGGER.log(Level.ERROR, "Harness Hook 审计失败", failure);
        }
    }
}
