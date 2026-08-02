package com.agent.web.log;

import com.agent.core.trace.RunLogEvent;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 将 Run 日志流与幂等清理动作绑定。 */
public final class RunLogSubscription implements AutoCloseable {

    private final Flux<RunLogEvent> events;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean();

    RunLogSubscription(Flux<RunLogEvent> events, Runnable closeAction) {
        this.events = Objects.requireNonNull(events, "events 不能为空");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction 不能为空");
    }

    /** 返回在终止时自动释放订阅的日志流。 */
    public Flux<RunLogEvent> events() {
        return events.doFinally(signal -> close());
    }

    /** 立即释放订阅；重复调用没有副作用。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeAction.run();
        }
    }
}
