package com.agent.core.llm;

/** 端点准入控制器的即时指标快照。 */
public record InferenceAdmissionSnapshot(
        int activeRequests,
        int requestsInWindow,
        long concurrencyRejections,
        long rateLimitRejections) {
}
