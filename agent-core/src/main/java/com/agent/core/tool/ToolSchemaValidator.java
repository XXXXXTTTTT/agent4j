package com.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** 工具 JSON Schema 定义和参数校验端口。 */
public interface ToolSchemaValidator {

    /** 校验工具定义使用的 Schema。 */
    void validateSchema(JsonNode schema);

    /** 根据已声明 Schema 校验调用参数。 */
    void validateArguments(JsonNode schema, JsonNode arguments);
}
