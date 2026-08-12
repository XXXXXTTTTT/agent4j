package com.agent.web.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建受治理 CLI Run 的结构化请求。 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CliRunRequest(
        @NotBlank String commandName,
        @NotNull @Size(max = 64) List<String> arguments,
        @Min(1) @Max(600) long timeoutSeconds) {

    /** 冻结有序参数，避免请求绑定后被改变。 */
    public CliRunRequest {
        arguments = List.copyOf(arguments);
    }
}
