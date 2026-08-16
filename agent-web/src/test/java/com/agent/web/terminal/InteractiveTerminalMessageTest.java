package com.agent.web.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractiveTerminalMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void decodesInputResizeInterruptAndCloseMessages() {
        assertThat(InteractiveTerminalMessage.decode(mapper, "{\"type\":\"input\",\"data\":\"ls\\r\"}"))
                .isEqualTo(new InteractiveTerminalMessage.Input("ls\r"));
        assertThat(InteractiveTerminalMessage.decode(mapper, "{\"type\":\"resize\",\"cols\":120,\"rows\":32}"))
                .isEqualTo(new InteractiveTerminalMessage.Resize(120, 32));
        assertThat(InteractiveTerminalMessage.decode(mapper, "{\"type\":\"interrupt\"}"))
                .isEqualTo(new InteractiveTerminalMessage.Interrupt());
        assertThat(InteractiveTerminalMessage.decode(mapper, "{\"type\":\"close\"}"))
                .isEqualTo(new InteractiveTerminalMessage.Close());
    }

    @Test
    void rejectsUnknownTypesOversizedInputAndInvalidResize() {
        assertThatThrownBy(() -> InteractiveTerminalMessage.decode(mapper, "{\"type\":\"exec\"}"))
                .hasMessageContaining("interactiveTerminalMessage.type");
        assertThatThrownBy(() -> InteractiveTerminalMessage.decode(mapper, "{\"type\":\"resize\",\"cols\":1,\"rows\":0}"))
                .hasMessageContaining("PTY 尺寸");
        String oversized = "x".repeat(65537);
        assertThatThrownBy(() -> InteractiveTerminalMessage.decode(mapper, mapper.writeValueAsString(
                java.util.Map.of("type", "input", "data", oversized))))
                .hasMessageContaining("data 长度");
    }
}
