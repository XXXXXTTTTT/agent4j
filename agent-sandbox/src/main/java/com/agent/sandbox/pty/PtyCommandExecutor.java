package com.agent.sandbox.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 使用 pty4j 执行一次本地 Bash 命令。 */
public final class PtyCommandExecutor {

    private static final Duration PROCESS_TERMINATION_TIMEOUT = Duration.ofSeconds(1);

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
        InputStream processOutput = process.getInputStream();

        Thread readerThread = Thread.ofVirtual()
                .name("pty-output-reader")
                .start(() -> readOutput(
                        process,
                        processOutput,
                        stdout,
                        logConsumer,
                        readFailure,
                        consumerFailure));

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        boolean timedOut = !finished;
        if (timedOut) {
            terminateProcessTree(process);
            awaitReader(readerThread, processOutput);
        } else {
            readerThread.join();
        }

        if (consumerFailure.get() != null) {
            throw new SandboxExecutionException("PTY 日志接收器执行失败", consumerFailure.get());
        }
        if (!timedOut && readFailure.get() != null) {
            throw new SandboxExecutionException("读取 PTY 输出失败", readFailure.get());
        }

        int exitCode = timedOut ? -1 : process.exitValue();
        return new CommandResult(exitCode, stdout.toString(), "", timedOut);
    }

    private void terminateProcessTree(PtyProcess process) throws InterruptedException {
        ProcessHandle root = ProcessHandle.of(process.pid()).orElse(null);
        List<ProcessHandle> descendants = root == null
                ? List.of()
                : root.descendants().toList();
        descendants.reversed().stream()
                .filter(ProcessHandle::isAlive)
                .forEach(ProcessHandle::destroyForcibly);
        if (root != null && root.isAlive()) {
            root.destroyForcibly();
        }

        CompletableFuture<Void> descendantsExited = CompletableFuture.allOf(
                java.util.stream.Stream.concat(
                                root == null ? java.util.stream.Stream.empty()
                                        : java.util.stream.Stream.of(root),
                                descendants.stream())
                        .map(ProcessHandle::onExit)
                        .toArray(CompletableFuture[]::new));
        try {
            descendantsExited.get(
                    PROCESS_TERMINATION_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            throw new SandboxExecutionException("等待 PTY 子进程退出失败", exception.getCause());
        } catch (TimeoutException exception) {
            throw new SandboxExecutionException("PTY 子进程未在限定时间内退出", exception);
        } finally {
            process.destroyForcibly();
        }
        // ProcessHandle 已确认 Bash 及其后代退出；WinPTY 包装进程的 waitFor
        // 在 Windows 上可能阻塞约 30 秒，因此这里只做有界等待。
        process.waitFor(100, TimeUnit.MILLISECONDS);
    }

    private void awaitReader(Thread readerThread, InputStream processOutput)
            throws InterruptedException {
        readerThread.join(PROCESS_TERMINATION_TIMEOUT.toMillis());
        if (!readerThread.isAlive()) {
            return;
        }
        readerThread.interrupt();
        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("pty-output-closer")
                .start(() -> closeOutput(processOutput, closeFuture));
        try {
            closeFuture.get(100, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            // WinPTY 关闭可能继续等待原生读取锁，不能阻塞命令结果返回。
        } catch (ExecutionException exception) {
            throw new SandboxExecutionException("关闭 PTY 输出流失败", exception.getCause());
        }
        readerThread.join(100);
    }

    private void closeOutput(InputStream processOutput, CompletableFuture<Void> closeFuture) {
        try {
            processOutput.close();
            closeFuture.complete(null);
        } catch (IOException exception) {
            closeFuture.completeExceptionally(exception);
        }
    }

    private void readOutput(
            PtyProcess process,
            InputStream processOutput,
            StringBuilder stdout,
            Consumer<TerminalLog> logConsumer,
            AtomicReference<IOException> readFailure,
            AtomicReference<RuntimeException> consumerFailure) {
        try (Reader reader = new InputStreamReader(
                processOutput, StandardCharsets.UTF_8)) {
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
