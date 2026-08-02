package com.agent.web.terminal;

import com.agent.core.trace.RunLogEvent;

import java.util.Objects;

/** 终端 SSE 与 WebSocket 共用的强类型传输帧。 */
public sealed interface TerminalFrame
        permits TerminalFrame.Snapshot, TerminalFrame.Log {

    /** 返回精确帧类别。 */
    String kind();

    /** 创建终端快照帧。 */
    static TerminalFrame snapshot(TerminalSnapshot terminal) {
        return new Snapshot("SNAPSHOT", terminal);
    }

    /** 创建实时日志帧。 */
    static TerminalFrame log(RunLogEvent event) {
        return new Log("LOG", event);
    }

    /** 终端快照帧。 */
    record Snapshot(String kind, TerminalSnapshot terminal) implements TerminalFrame {

        /** 校验快照帧。 */
        public Snapshot {
            if (!"SNAPSHOT".equals(kind)) {
                throw new IllegalArgumentException("kind 必须为 SNAPSHOT");
            }
            Objects.requireNonNull(terminal, "terminal 不能为空");
        }
    }

    /** 实时日志帧。 */
    record Log(String kind, RunLogEvent event) implements TerminalFrame {

        /** 校验日志帧。 */
        public Log {
            if (!"LOG".equals(kind)) {
                throw new IllegalArgumentException("kind 必须为 LOG");
            }
            Objects.requireNonNull(event, "event 不能为空");
        }
    }
}
