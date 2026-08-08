package com.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSchemaValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSchemaValidator validator = new JacksonToolSchemaValidator();

    @Test
    void acceptsTheExactSupportedSchemaSubset() throws Exception {
        JsonNode schema = schema();

        assertThatCode(() -> validator.validateSchema(schema)).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidSchemaWithExactJsonPointers() throws Exception {
        assertSchemaFailure("[]", "/");
        assertSchemaFailure("{\"type\":\"string\"}", "/type");
        assertSchemaFailure("{\"type\":\"object\",\"format\":\"java\"}", "/format");
        assertSchemaFailure("""
                {"type":"object","properties":{"items":{"type":"array"}}}
                """, "/properties/items/items");
        assertSchemaFailure("""
                {"type":"object","properties":{"path":{"type":"uri"}}}
                """, "/properties/path/type");
        assertSchemaFailure("""
                {"type":"object","properties":{"":{"type":"string"}}}
                """, "/properties/");
        assertSchemaFailure("""
                {"type":"object","properties":{"path":{"type":"string"}},
                 "required":["path","path"]}
                """, "/required/1");
        assertSchemaFailure("""
                {"type":"object","properties":{},"required":["path"]}
                """, "/required/0");
        assertSchemaFailure("""
                {"type":"object","properties":{"path":{
                  "type":"string","minLength":5,"maxLength":2}}}
                """, "/properties/path/minLength");
        assertSchemaFailure("""
                {"type":"object","properties":{"path":{
                  "type":"string","minLength":1.5}}}
                """, "/properties/path/minLength");
        assertSchemaFailure("""
                {"type":"object","properties":{"count":{
                  "type":"integer","minimum":4,"maximum":2}}}
                """, "/properties/count/minimum");
        assertSchemaFailure("""
                {"type":"object","properties":{"mode":{"type":"string","enum":[]}}}
                """, "/properties/mode/enum");
    }

    @Test
    void validatesNestedArgumentsAndAllSupportedTypes() throws Exception {
        JsonNode valid = objectMapper.readTree("""
                {
                  "path":"src/App.java",
                  "count":2,
                  "ratio":0.5,
                  "enabled":true,
                  "mode":"read",
                  "tags":["java","agent"],
                  "nested":{"value":"ok"},
                  "extraAllowed":"kept"
                }
                """);

        assertThatCode(() -> validator.validateArguments(schema(), valid))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsArgumentViolationsWithExactJsonPointers() throws Exception {
        assertArgumentFailure("""
                {"count":2,"ratio":0.5,"enabled":true,"mode":"read",
                 "tags":["java"],"nested":{"value":"ok"}}
                """, "/path");
        assertArgumentFailure("""
                {"path":"src/App.java","count":1.5,"ratio":0.5,"enabled":true,
                 "mode":"read","tags":["java"],"nested":{"value":"ok"}}
                """, "/count");
        assertArgumentFailure("""
                {"path":"012345678901234567890","count":2,"ratio":0.5,"enabled":true,
                 "mode":"read","tags":["java"],"nested":{"value":"ok"}}
                """, "/path");
        assertArgumentFailure("""
                {"path":"App.java","count":2,"ratio":0.5,"enabled":true,
                 "mode":"write","tags":["java"],"nested":{"value":"ok"}}
                """, "/mode");
        assertArgumentFailure("""
                {"path":"App.java","count":2,"ratio":0.5,"enabled":true,
                 "mode":"read","tags":[1],"nested":{"value":"ok"}}
                """, "/tags/0");
        assertArgumentFailure("""
                {"path":"App.java","count":2,"ratio":0.5,"enabled":true,
                 "mode":"read","tags":["java"],"nested":{"value":"ok","extra":1}}
                """, "/nested/extra");
    }

    @Test
    void rejectsNonFiniteNumbers() throws Exception {
        JsonNode arguments = objectMapper.readTree("""
                {"path":"App.java","count":2,"ratio":0.5,"enabled":true,
                 "mode":"read","tags":["java"],"nested":{"value":"ok"}}
                """);
        ((com.fasterxml.jackson.databind.node.ObjectNode) arguments)
                .set("ratio", JsonNodeFactory.instance.numberNode(Double.NaN));

        assertThatThrownBy(() -> validator.validateArguments(schema(), arguments))
                .isInstanceOfSatisfying(ToolSchemaException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.jsonPointer())
                                .isEqualTo("/ratio"));
    }

    private JsonNode schema() throws Exception {
        return objectMapper.readTree("""
                {
                  "type":"object",
                  "title":"文件查询",
                  "description":"读取源码",
                  "properties":{
                    "path":{"type":"string","minLength":1,"maxLength":20},
                    "count":{"type":"integer","minimum":1,"maximum":3},
                    "ratio":{"type":"number","minimum":0,"maximum":1},
                    "enabled":{"type":"boolean"},
                    "mode":{"type":"string","enum":["read","inspect"]},
                    "tags":{"type":"array","items":{"type":"string","minLength":1}},
                    "nested":{"type":"object","properties":{
                      "value":{"type":"string"}},"required":["value"],
                      "additionalProperties":false}
                  },
                  "required":["path","count","ratio","enabled","mode","tags","nested"]
                }
                """);
    }

    private void assertSchemaFailure(String json, String pointer) throws Exception {
        JsonNode schema = objectMapper.readTree(json);
        assertThatThrownBy(() -> validator.validateSchema(schema))
                .isInstanceOfSatisfying(ToolSchemaException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.jsonPointer())
                                .isEqualTo(pointer));
    }

    private void assertArgumentFailure(String json, String pointer) throws Exception {
        JsonNode arguments = objectMapper.readTree(json);
        assertThatThrownBy(() -> validator.validateArguments(schema(), arguments))
                .isInstanceOfSatisfying(ToolSchemaException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.jsonPointer())
                                .isEqualTo(pointer));
    }
}
