package com.agent.core.observability;

import java.util.Objects;
import java.util.Optional;

/** 一次成功模型调用可提供的响应元数据。 */
public record ModelCallSuccess(
        Optional<String> responseModel,
        Optional<ModelUsage> usage) {

    /** 校验成功响应元数据。 */
    public ModelCallSuccess {
        Objects.requireNonNull(responseModel, "responseModel 不能为空");
        Objects.requireNonNull(usage, "usage 不能为空");
        if (responseModel.isPresent() && responseModel.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("responseModel 不能为空");
        }
    }
}
