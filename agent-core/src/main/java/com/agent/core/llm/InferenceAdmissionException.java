package com.agent.core.llm;

import java.util.Objects;

/** 模型端点拒绝本次请求。 */
public final class InferenceAdmissionException extends RuntimeException {

    private final InferenceRejectionReason reason;

    /** 创建带强类型原因的准入异常。 */
    public InferenceAdmissionException(
            InferenceRejectionReason reason,
            String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason 不能为空");
    }

    /** 返回拒绝原因。 */
    public InferenceRejectionReason reason() {
        return reason;
    }
}
