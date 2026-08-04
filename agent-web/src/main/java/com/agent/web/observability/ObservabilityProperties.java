package com.agent.web.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

/** Langfuse OTLP 观测导出的强类型配置。 */
@ConfigurationProperties(prefix = "agent.observability")
public record ObservabilityProperties(
        boolean enabled,
        String serviceName,
        String otlpTracesEndpoint,
        String authorization,
        Duration exportTimeout) {

    /** 应用设计文档规定的默认配置。 */
    public ObservabilityProperties {
        serviceName = serviceName == null ? "agent-runtime-system" : serviceName;
        otlpTracesEndpoint = otlpTracesEndpoint == null ? "" : otlpTracesEndpoint;
        authorization = authorization == null ? "" : authorization;
        exportTimeout = exportTimeout == null ? Duration.ofSeconds(10) : exportTimeout;
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("agent.observability.service-name 不能为空");
        }
    }

    /** 校验启用状态下的完整 OTLP 配置。 */
    public void validate() {
        if (exportTimeout.isZero() || exportTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "agent.observability.export-timeout 必须大于 0");
        }
        if (!enabled) {
            return;
        }
        if (otlpTracesEndpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "agent.observability.otlp-traces-endpoint 不能为空");
        }
        URI endpoint;
        try {
            endpoint = new URI(otlpTracesEndpoint);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                    "agent.observability.otlp-traces-endpoint 必须为绝对 HTTP/HTTPS URI",
                    exception);
        }
        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute()
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "agent.observability.otlp-traces-endpoint 必须为绝对 HTTP/HTTPS URI");
        }
        if (authorization.isBlank()) {
            throw new IllegalArgumentException(
                    "agent.observability.authorization 不能为空");
        }
    }
}
