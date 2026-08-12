package com.agent.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** MCP Docker 运行时独立资源与物料配置。 */
@ConfigurationProperties(prefix = "agent.mcp.runtime")
public record McpRuntimeProperties(
        Path materialRoot,
        String materialContainerDirectory,
        String materialSourceContainer,
        String materialSourcePath,
        String pythonPreparationImage,
        String image,
        String containerWorkingDirectory,
        long memoryBytes,
        long nanoCpus,
        long pidsLimit,
        int maxStdoutFrameBytes,
        int maxStdoutBufferedBytes,
        int maxStderrBytes,
        long maxMaterialBytes,
        Duration preparationTimeout,
        Duration requestTimeout,
        Duration toolTimeout,
        Duration drainTimeout) {
    public McpRuntimeProperties {
        materialRoot = materialDirectory(materialRoot);
        materialContainerDirectory = text(materialContainerDirectory, "materialContainerDirectory");
        materialSourceContainer = optionalText(materialSourceContainer);
        materialSourcePath = optionalText(materialSourcePath);
        pythonPreparationImage = optionalText(pythonPreparationImage);
        if (materialSourceContainer.isBlank() != materialSourcePath.isBlank()) {
            throw new IllegalArgumentException("materialSourceContainer 与 materialSourcePath 必须同时配置");
        }
        image = text(image, "image");
        containerWorkingDirectory = text(containerWorkingDirectory, "containerWorkingDirectory");
        if (memoryBytes <= 0 || nanoCpus <= 0 || pidsLimit <= 0 || maxMaterialBytes <= 0
                || maxStdoutFrameBytes <= 0 || maxStdoutBufferedBytes <= 0 || maxStderrBytes <= 0) {
            throw new IllegalArgumentException("MCP 运行资源限制必须为正数");
        }
        positive(preparationTimeout, "preparationTimeout");
        positive(requestTimeout, "requestTimeout");
        positive(toolTimeout, "toolTimeout");
        positive(drainTimeout, "drainTimeout");
    }

    private static Path materialDirectory(Path value) {
        Objects.requireNonNull(value, "materialRoot 不能为空");
        try {
            Path real = Files.createDirectories(value).toRealPath();
            if (!Files.isDirectory(real)) throw new IllegalArgumentException("materialRoot 必须是已有目录");
            return real;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("materialRoot 无法创建为目录", exception);
        }
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
    }
}
