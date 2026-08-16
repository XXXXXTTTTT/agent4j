package com.agent.web.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Set;

/** 客户端到交互终端的严格控制消息协议。 */
public sealed interface InteractiveTerminalMessage
        permits InteractiveTerminalMessage.Input,
        InteractiveTerminalMessage.Resize,
        InteractiveTerminalMessage.Interrupt,
        InteractiveTerminalMessage.Close {

    static InteractiveTerminalMessage decode(ObjectMapper mapper, String payload) {
        Objects.requireNonNull(mapper, "mapper 不能为空");
        Objects.requireNonNull(payload, "payload 不能为空");
        try {
            JsonNode object = mapper.readTree(payload);
            if (object == null || !object.isObject() || object.size() == 0) {
                throw new IllegalArgumentException("interactiveTerminalMessage 必须是非空对象");
            }
            String type = requiredText(object, "type");
            return switch (type) {
                case "input" -> {
                    requireExactKeys(object, Set.of("type", "data"));
                    yield new Input(requiredText(object, "data"));
                }
                case "resize" -> {
                    requireExactKeys(object, Set.of("type", "cols", "rows"));
                    yield new Resize(requiredInt(object, "cols"), requiredInt(object, "rows"));
                }
                case "interrupt" -> {
                    requireExactKeys(object, Set.of("type"));
                    yield new Interrupt();
                }
                case "close" -> {
                    requireExactKeys(object, Set.of("type"));
                    yield new Close();
                }
                default -> throw new IllegalArgumentException("interactiveTerminalMessage.type 包含未知值: " + type);
            };
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("interactiveTerminalMessage JSON 无效", exception);
        }
    }

    private static String requiredText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IllegalArgumentException("interactiveTerminalMessage." + name + " 必须是非空字符串");
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException("interactiveTerminalMessage." + name + " 必须是整数");
        }
        return value.intValue();
    }

    private static void requireExactKeys(JsonNode object, Set<String> expected) {
        java.util.Set<String> actual = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("interactiveTerminalMessage 字段不匹配: " + actual);
        }
    }

    record Input(String data) implements InteractiveTerminalMessage {
        public Input {
            Objects.requireNonNull(data, "data 不能为空");
            if (data.isEmpty() || data.length() > 65536) throw new IllegalArgumentException("data 长度必须在 1 到 65536 之间");
        }
    }

    record Resize(int cols, int rows) implements InteractiveTerminalMessage {
        public Resize {
            if (cols < 2 || rows < 1 || cols > 500 || rows > 300) throw new IllegalArgumentException("PTY 尺寸超出允许范围");
        }
    }

    record Interrupt() implements InteractiveTerminalMessage { }

    record Close() implements InteractiveTerminalMessage { }
}
