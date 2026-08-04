package com.agent.web.observability;

import com.agent.core.observability.ModelCallObserver;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 Langfuse OTLP HTTP 导出及关闭语义。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class OpenTelemetryConfiguration {

    /** 观测关闭时提供无副作用的模型调用观测器。 */
    @Bean
    @ConditionalOnProperty(
            name = "agent.observability.enabled",
            havingValue = "false",
            matchIfMissing = true)
    ModelCallObserver noopModelCallObserver() {
        return ModelCallObserver.noop();
    }

    /** 创建使用完整 endpoint 的 OTLP HTTP exporter。 */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "agent.observability.enabled", havingValue = "true")
    OtlpHttpSpanExporter otlpHttpSpanExporter(ObservabilityProperties properties) {
        properties.validate();
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.otlpTracesEndpoint())
                .addHeader("Authorization", properties.authorization())
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(properties.exportTimeout())
                .build();
    }

    /** 创建批量 Span processor。 */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "agent.observability.enabled", havingValue = "true")
    BatchSpanProcessor batchSpanProcessor(OtlpHttpSpanExporter exporter) {
        return BatchSpanProcessor.builder(exporter).build();
    }

    /** 创建带 service.name Resource 的 SDK provider。 */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "agent.observability.enabled", havingValue = "true")
    SdkTracerProvider sdkTracerProvider(
            BatchSpanProcessor processor,
            ObservabilityProperties properties) {
        Resource resource = Resource.create(io.opentelemetry.api.common.Attributes.of(
                AttributeKey.stringKey("service.name"), properties.serviceName()));
        return SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(processor)
                .build();
    }

    /** 创建 OpenTelemetry Run Trace 与模型观测适配器。 */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(name = "agent.observability.enabled", havingValue = "true")
    OpenTelemetryRunTracePublisher openTelemetryRunTracePublisher(
            SdkTracerProvider provider) {
        return new OpenTelemetryRunTracePublisher(provider.get("agent-runtime-system"));
    }

    /** 以固定顺序关闭活动 Span、刷新导出队列并关闭 SDK。 */
    @Bean
    @ConditionalOnProperty(name = "agent.observability.enabled", havingValue = "true")
    OpenTelemetryShutdown openTelemetryShutdown(
            OpenTelemetryRunTracePublisher publisher,
            SdkTracerProvider provider,
            ObservabilityProperties properties) {
        return new OpenTelemetryShutdown(publisher, provider, properties);
    }

    static final class OpenTelemetryShutdown implements AutoCloseable {
        private final OpenTelemetryRunTracePublisher publisher;
        private final SdkTracerProvider provider;
        private final ObservabilityProperties properties;

        private OpenTelemetryShutdown(
                OpenTelemetryRunTracePublisher publisher,
                SdkTracerProvider provider,
                ObservabilityProperties properties) {
            this.publisher = publisher;
            this.provider = provider;
            this.properties = properties;
        }

        @Override
        public void close() {
            publisher.close();
            try {
                provider.forceFlush().join(
                        properties.exportTimeout().toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {
                provider.shutdown().join(
                        properties.exportTimeout().toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }
    }
}
