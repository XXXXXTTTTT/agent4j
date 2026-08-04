package com.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 将 Benchmark 报告稳定序列化为 UTF-8 JSON。 */
public final class BenchmarkReportWriter {

    private final ObjectMapper objectMapper;

    public BenchmarkReportWriter() {
        this(new ObjectMapper());
    }

    public BenchmarkReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空")
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** 写入输出流但不关闭调用方持有的流。 */
    public void write(BenchmarkReport report, OutputStream output) {
        Objects.requireNonNull(report, "report 不能为空");
        Objects.requireNonNull(output, "output 不能为空");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, report);
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入 Benchmark 报告失败", exception);
        }
    }

    /** 以 UTF-8 写入目标文件。 */
    public void write(BenchmarkReport report, Path path) {
        Objects.requireNonNull(path, "path 不能为空");
        try (OutputStream output = Files.newOutputStream(path)) {
            write(report, output);
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入 Benchmark 报告失败", exception);
        }
    }
}
