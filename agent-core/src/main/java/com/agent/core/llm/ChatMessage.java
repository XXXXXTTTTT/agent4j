package com.agent.core.llm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * OpenAI 聊天消息。
 *
 * @param role       消息角色
 * @param content    消息正文，工具调用消息允许为空
 * @param name       消息发送方名称
 * @param toolCallId 工具结果关联的调用标识
 * @param toolCalls  助手发起的工具调用
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        Role role,
        Content content,
        String name,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @JsonProperty("tool_calls") List<ToolCall> toolCalls) {

    /**
     * 创建消息并冻结工具调用列表。
     */
    public ChatMessage {
        Objects.requireNonNull(role, "role 不能为空");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 创建系统消息。
     *
     * @param content 系统指令
     * @return 系统消息
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(
                Role.SYSTEM, new TextContent(requireContent(content)), null, null, List.of());
    }

    /**
     * 创建用户消息。
     *
     * @param content 用户输入
     * @return 用户消息
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(
                Role.USER, new TextContent(requireContent(content)), null, null, List.of());
    }

    /**
     * 创建助手文本消息。
     *
     * @param content 助手输出
     * @return 助手消息
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(
                Role.ASSISTANT, new TextContent(requireContent(content)), null, null, List.of());
    }

    /**
     * 创建多模态用户消息。
     *
     * @param parts 文本与图像内容块
     * @return 多模态用户消息
     */
    public static ChatMessage userMultimodal(List<ContentPart> parts) {
        return new ChatMessage(
                Role.USER,
                new MultimodalContent(parts),
                null,
                null,
                List.of());
    }

    /**
     * 创建助手工具调用消息。
     *
     * @param toolCalls 工具调用列表
     * @return 助手工具调用消息
     */
    public static ChatMessage assistantToolCalls(List<ToolCall> toolCalls) {
        Objects.requireNonNull(toolCalls, "toolCalls 不能为空");
        if (toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls 不能为空列表");
        }
        return new ChatMessage(Role.ASSISTANT, null, null, null, toolCalls);
    }

    /**
     * 创建工具结果消息。
     *
     * @param toolCallId 工具调用标识
     * @param content    工具执行结果
     * @return 工具结果消息
     */
    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(
                Role.TOOL,
                new TextContent(requireContent(content)),
                null,
                Objects.requireNonNull(toolCallId, "toolCallId 不能为空"),
                List.of());
    }

    private static String requireContent(String content) {
        return Objects.requireNonNull(content, "content 不能为空");
    }

    /** OpenAI 消息内容。 */
    @JsonSerialize(using = ChatMessageContentSerializer.class)
    @JsonDeserialize(using = ChatMessageContentDeserializer.class)
    public sealed interface Content permits TextContent, MultimodalContent {
    }

    /** 纯文本消息内容。 */
    public record TextContent(String text) implements Content {

        /** 校验纯文本内容。 */
        public TextContent {
            Objects.requireNonNull(text, "text 不能为空");
        }
    }

    /** 多模态消息内容。 */
    public record MultimodalContent(List<ContentPart> parts) implements Content {

        /** 冻结多模态内容块。 */
        public MultimodalContent {
            Objects.requireNonNull(parts, "parts 不能为空");
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("parts 不能为空列表");
            }
            parts = List.copyOf(parts);
        }
    }

    /** 多模态内容块。 */
    public sealed interface ContentPart permits TextPart, ImageUrlPart {
    }

    /** 文本内容块。 */
    public record TextPart(String text) implements ContentPart {

        /** 校验文本内容块。 */
        public TextPart {
            Objects.requireNonNull(text, "text 不能为空");
        }
    }

    /** 图像 URL 内容块。 */
    public record ImageUrlPart(ImageUrl imageUrl) implements ContentPart {

        /** 校验图像 URL 内容块。 */
        public ImageUrlPart {
            Objects.requireNonNull(imageUrl, "imageUrl 不能为空");
        }
    }

    /** OpenAI 图像 URL 内容。 */
    public record ImageUrl(String url, ImageDetail detail) {

        /** 校验图像 URL。 */
        public ImageUrl {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("url 不能为空");
            }
            Objects.requireNonNull(detail, "detail 不能为空");
        }
    }

    /** 图像分析细节等级。 */
    public enum ImageDetail {
        AUTO("auto"),
        LOW("low"),
        HIGH("high");

        private final String jsonValue;

        ImageDetail(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        /** 返回协议中的精确细节值。 */
        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }

        /**
         * 从协议值解析细节等级。
         *
         * @param value 协议值
         * @return 对应细节等级
         */
        @JsonCreator
        public static ImageDetail fromJson(String value) {
            return Arrays.stream(values())
                    .filter(detail -> detail.jsonValue.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知图像细节: " + value));
        }
    }

    /**
     * OpenAI 消息角色。
     */
    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool");

        private final String jsonValue;

        Role(String jsonValue) {
            this.jsonValue = jsonValue;
        }

        /**
         * 返回协议中的精确角色值。
         *
         * @return 小写角色值
         */
        @JsonValue
        public String jsonValue() {
            return jsonValue;
        }

        /**
         * 从协议值解析角色。
         *
         * @param value 协议角色值
         * @return 对应角色
         */
        @JsonCreator
        public static Role fromJson(String value) {
            return Arrays.stream(values())
                    .filter(role -> role.jsonValue.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知消息角色: " + value));
        }
    }

    /**
     * 助手发起的单次工具调用。
     *
     * @param id       调用标识
     * @param type     调用类型
     * @param function 函数调用内容
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(String id, String type, FunctionCall function) {

        /**
         * 校验完整工具调用。
         */
        public ToolCall {
            Objects.requireNonNull(id, "id 不能为空");
            Objects.requireNonNull(type, "type 不能为空");
            Objects.requireNonNull(function, "function 不能为空");
        }
    }

    /**
     * 函数名称与 JSON 参数。
     *
     * @param name      函数名称
     * @param arguments JSON 参数字符串
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(String name, String arguments) {

        /**
         * 校验函数调用内容。
         */
        public FunctionCall {
            Objects.requireNonNull(name, "name 不能为空");
            Objects.requireNonNull(arguments, "arguments 不能为空");
        }
    }
}
