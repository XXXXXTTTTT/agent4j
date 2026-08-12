package com.agent.web.mcp.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerMcpStdioProcessTest {
    @Test
    void failureClosesAllStreamsAndIsObservable() throws IOException {
        OutputStream stdin = new OutputStream() {
            private boolean closed;
            public void write(int b) throws IOException { if (closed) throw new IOException("closed"); }
            public void close() { closed = true; }
        };
        ByteArrayInputStream stdout = new ByteArrayInputStream(new byte[0]);
        ByteArrayInputStream stderr = new ByteArrayInputStream(new byte[0]);
        AtomicInteger destroyed = new AtomicInteger();
        DockerMcpStdioProcess process = new DockerMcpStdioProcess(
                stdout, stdin, stderr, destroyed::incrementAndGet, (reason, failure) -> { });

        RuntimeException failure = new RuntimeException("attach disconnected");
        process.fail(McpRuntimeFailureListener.Reason.ATTACH_DISCONNECTED, failure);
        process.destroy();

        assertThat(process.isAlive()).isFalse();
        assertThat(destroyed).hasValue(1);
        assertThatThrownBy(() -> process.stdin().write(1)).isInstanceOf(IOException.class);
    }
}
