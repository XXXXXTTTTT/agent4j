package com.agent.sandbox.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 持有一个工作区绑定的交互式 PTY 会话，并隔离输入、输出和进程清理。 */
public final class InteractivePtySession implements AutoCloseable {

    private static final int DEFAULT_COLUMNS = 120;
    private static final int DEFAULT_ROWS = 32;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(1);

    private final PtyProcess process;
    private final OutputStream input;
    private final Consumer<String> outputConsumer;
    private final Consumer<Integer> exitConsumer;
    private final AtomicBoolean closed = new AtomicBoolean();

    private InteractivePtySession(
            PtyProcess process,
            Consumer<String> outputConsumer,
            Consumer<Integer> exitConsumer) {
        this.process = process;
        this.input = process.getOutputStream();
        this.outputConsumer = outputConsumer;
        this.exitConsumer = exitConsumer;
    }

    /** 启动交互式 Bash PTY，并在虚拟线程中持续转发 UTF-8 输出。 */
    public static InteractivePtySession start(
            PtyTarget target,
            Consumer<String> outputConsumer,
            Consumer<Integer> exitConsumer) {
        Objects.requireNonNull(target, "target 不能为空");
        Objects.requireNonNull(outputConsumer, "outputConsumer 不能为空");
        Objects.requireNonNull(exitConsumer, "exitConsumer 不能为空");
        try {
            PtyProcess process = new PtyProcessBuilder()
                    .setCommand(new String[] { target.bashExecutable().toString(), "-i" })
                    .setDirectory(target.workingDirectory().toString())
                    .setRedirectErrorStream(true)
                    .setInitialColumns(DEFAULT_COLUMNS)
                    .setInitialRows(DEFAULT_ROWS)
                    .setWindowsAnsiColorEnabled(true)
                    .start();
            InteractivePtySession session = new InteractivePtySession(process, outputConsumer, exitConsumer);
            session.startReader();
            return session;
        } catch (IOException exception) {
            throw new SandboxExecutionException("交互式 PTY 启动失败", exception);
        }
    }

    /** 向 PTY 写入用户键盘字节。 */
    public synchronized void write(String text) {
        Objects.requireNonNull(text, "text 不能为空");
        ensureOpen();
        try {
            input.write(text.getBytes(StandardCharsets.UTF_8));
            input.flush();
        } catch (IOException exception) {
            throw new SandboxExecutionException("写入交互式 PTY 失败", exception);
        }
    }

    /** 更新 PTY 的列数和行数，使全屏 CLI 能正确重排。 */
    public void resize(int columns, int rows) {
        if (columns < 2 || rows < 1) {
            throw new IllegalArgumentException("PTY 尺寸必须为 columns >= 2 且 rows >= 1");
        }
        ensureOpen();
        try {
            process.setWinSize(new WinSize(columns, rows));
        } catch (RuntimeException exception) {
            throw new SandboxExecutionException("调整交互式 PTY 尺寸失败", exception);
        }
    }

    /** 向当前 shell 发送 Ctrl+C。 */
    public void interrupt() {
        write("\u0003");
    }

    /** 返回会话是否已关闭。 */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // 进程清理继续执行，关闭阶段无需覆盖原始退出原因。
        }
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startReader() {
        Thread.ofVirtual().name("interactive-pty-output-reader").start(() -> readOutput(process.getInputStream()));
    }

    private void readOutput(InputStream output) {
        try (output) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = output.read(buffer)) >= 0) {
                if (count == 0) continue;
                outputConsumer.accept(new String(buffer, 0, count, StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            if (!closed.get()) outputConsumer.accept("\r\n[终端读取失败] " + exception.getMessage() + "\r\n");
        } finally {
            if (closed.compareAndSet(false, true)) {
                int exitCode = process.isAlive() ? -1 : process.exitValue();
                exitConsumer.accept(exitCode);
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("交互式 PTY 已关闭");
    }
}
