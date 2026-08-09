package com.agent.core.gui;

import com.agent.sandbox.browser.BrowserAutomation;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 按 Run 管理独占浏览器会话。 */
public final class BrowserSessionRegistry implements AutoCloseable {

    private final Supplier<BrowserAutomation> sessionFactory;
    private final ConcurrentMap<UUID, BrowserAutomation> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 注入浏览器会话工厂。 */
    public BrowserSessionRegistry(Supplier<BrowserAutomation> sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory 不能为空");
    }

    /** 为精确 Run 创建并注册独占会话。 */
    public synchronized BrowserAutomation open(UUID runId) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId 不能为空");
        if (sessions.containsKey(runId)) {
            throw new IllegalStateException("Run 已存在浏览器会话: " + runId);
        }
        BrowserAutomation session = Objects.requireNonNull(
                sessionFactory.get(), "sessionFactory 不得返回 null");
        sessions.put(runId, session);
        return session;
    }

    /** 获取精确 Run 的已注册会话。 */
    public BrowserAutomation require(UUID runId) {
        ensureOpen();
        Objects.requireNonNull(runId, "runId 不能为空");
        BrowserAutomation session = sessions.get(runId);
        if (session == null) {
            throw new IllegalStateException("Run 不存在浏览器会话: " + runId);
        }
        return session;
    }

    /** 移除并关闭精确 Run 的会话，未知 Run 不产生副作用。 */
    public void close(UUID runId) {
        Objects.requireNonNull(runId, "runId 不能为空");
        BrowserAutomation session = sessions.remove(runId);
        if (session != null) {
            session.close();
        }
    }

    /** 关闭全部会话，并把后续清理失败附加到首个异常。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        for (UUID runId : new ArrayList<>(sessions.keySet())) {
            try {
                close(runId);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("BrowserSessionRegistry 已关闭");
        }
    }
}
