package com.agent.web.terminal;

import java.util.Objects;

/** 服务端到客户端的交互终端事件帧。 */
public sealed interface InteractiveTerminalFrame
        permits InteractiveTerminalFrame.Ready,
        InteractiveTerminalFrame.Output,
        InteractiveTerminalFrame.Exit,
        InteractiveTerminalFrame.Error {

    record Ready(String type, String sessionId, String cwd, String shell) implements InteractiveTerminalFrame {
        public Ready {
            if (!"ready".equals(type)) throw new IllegalArgumentException("type 必须为 ready");
            Objects.requireNonNull(sessionId, "sessionId 不能为空");
            Objects.requireNonNull(cwd, "cwd 不能为空");
            Objects.requireNonNull(shell, "shell 不能为空");
        }
    }

    record Output(String type, String data) implements InteractiveTerminalFrame {
        public Output {
            if (!"output".equals(type)) throw new IllegalArgumentException("type 必须为 output");
            Objects.requireNonNull(data, "data 不能为空");
        }
    }

    record Exit(String type, Integer exitCode) implements InteractiveTerminalFrame {
        public Exit {
            if (!"exit".equals(type)) throw new IllegalArgumentException("type 必须为 exit");
        }
    }

    record Error(String type, String message) implements InteractiveTerminalFrame {
        public Error {
            if (!"error".equals(type)) throw new IllegalArgumentException("type 必须为 error");
            Objects.requireNonNull(message, "message 不能为空");
        }
    }
}
