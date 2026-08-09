package com.agent.core.llm;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** 不含凭据的可移植模型服务描述。 */
public record InferenceServiceContract(
        String endpointName,
        String model,
        InferenceProtocol protocol,
        Set<InferenceCapability> capabilities) {

    /** 校验字段并冻结能力集合。 */
    public InferenceServiceContract {
        if (endpointName == null || endpointName.isBlank()) {
            throw new IllegalArgumentException("endpointName 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        Objects.requireNonNull(protocol, "protocol 不能为空");
        capabilities = Set.copyOf(Objects.requireNonNull(
                capabilities, "capabilities 不能为空"));
    }

    /** 兼容旧端点构造器的完整能力声明。 */
    public static Set<InferenceCapability> allCapabilities() {
        return Set.copyOf(EnumSet.allOf(InferenceCapability.class));
    }
}
