package com.agent.sandbox.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 使用 pty4j 执行一次本地 Bash 命令。 */
public final class PtyCommandExecutor {

    /**
     * 执行 Bash 命令并捕获合并后的 PTY 输出。
     *
     * @param target      PTY 目标
     * @param bashCommand Bash 命令
     * @param timeout     超时时间
     * @param logConsumer 实时日志接收器
     * @return 命令结果
     */
    public CommandResult execute(
            PtyTarget target,
            String bashCommand,
            Duration timeout,
            Consumer<TerminalLog> logConsumer) {
        CommandRequest request = new CommandRequest(target, bashCommand, timeout);
        Objects.requireNonNull(logConsumer, "logConsumer 不能为空");

        PtyProcess process = null;
        try {
            process = new PtyProcessBuilder()
                    .setCommand(new String[] {
                            target.bashExecutable().toString(),
                            "-lc",
                            request.bashCommand()})
                    .setDirectory(target.workingDirectory().toString())
                    .setRedirectErrorStream(true)
                    .setWindowsAnsiColorEnabled(true)
                    .start();
            return awaitResult(process, request.timeout(), logConsumer);
        } catch (InterruptedException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new SandboxExecutionException("PTY 命令等待被中断", exception);
        } catch (SandboxExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SandboxExecutionException("PTY 命令执行失败", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private CommandResult awaitResult(
            PtyProcess process,
            Duration timeout,
            Consumer<TerminalLog> logConsumer) throws InterruptedException {
        StringBuilder stdout = new StringBuilder();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        AtomicReference<RuntimeException> consumerFailure = new AtomicReference<>();

        Thread readerThread = Thread.ofVirtual()
                .name("pty-output-reader")
                .start(() -> readOutput(
                        process, stdout, logConsumer, readFailure, consumerFailure));

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean timedOut = !finished;
        if (timedOut) {
            process.destroyForcibly();
            process.waitFor();
        }
        readerThread.join();

        if (consumerFailure.get() != null) {
            throw new SandboxExecutionException("PTY 日志接收器执行失败", consumerFailure.get());
        }
        if (!timedOut && readFailure.get() != null) {
            throw new SandboxExecutionException("读取 PTY 输出失败", readFailure.get());
        }

        int exitCode = timedOut ? -1 : process.exitValue();
        return new CommandResult(exitCode, stdout.toString(), "", timedOut);
    }

    private void readOutput(
            PtyProcess process,
            StringBuilder stdout,
            Consumer<TerminalLog> logConsumer,
            AtomicReference<IOException> readFailure,
            AtomicReference<RuntimeException> consumerFailure) {
        try (Reader reader = new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count == 0) {
                    continue;
                }
                String text = new String(buffer, 0, count);
                stdout.append(text);
                try {
                    logConsumer.accept(new TerminalLog(Stream.PTY, text));
                } catch (RuntimeException exception) {
                    consumerFailure.compareAndSet(null, exception);
                    process.destroyForcibly();
                    return;
                }
            }
        } catch (IOException exception) {
            readFailure.compareAndSet(null, exception);
        }
    }
}
