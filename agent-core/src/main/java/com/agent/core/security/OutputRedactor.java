package com.agent.core.security;

import com.fasterxml.jackson.databind.JsonNode;

/** 工具输出脱敏端口。 */
public interface OutputRedactor {

    /** 返回不修改输入节点的脱敏副本。 */
    JsonNode redact(String toolName, JsonNode output);
}
