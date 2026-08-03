package com.agent.rag.memory;

import com.agent.core.llm.ChatMessage;
import com.agent.core.llm.ModelRequest;
import com.agent.core.llm.ModelRouter;
import com.agent.core.llm.RoutedCompletion;
import com.agent.core.llm.TaskType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** 通过快速分类模型提取严格 JSON 长期记忆。 */
public final class ModelMemoryExtractor implements MemoryExtractor {

    private static final String SYSTEM_INSTRUCTION = """
            你负责提取可长期复用的用户事实。只保留用户确认的编码偏好、项目架构规范和已经发生的 Bad Case。
            不要保存临时命令、密钥、个人隐私或模型猜测。只能返回一个 JSON 对象，且只能包含 memories 字段。
            memories 必须是数组，每项只能包含 type、title、content；type 只能是 USER_PREFERENCE、
            ARCHITECTURE_RULE、BAD_CASE 之一；title 和 content 必须是字符串。
            """;

    private final ModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    /** 创建模型记忆提取器。 */
    public ModelMemoryExtractor(ModelRouter modelRouter, ObjectMapper objectMapper) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
    }

    /** 提取并严格校验模型返回的长期记忆数组。 */
    @Override
    public List<MemoryDraft> extract(MemoryCapture capture) {
        Objects.requireNonNull(capture, "capture 不能为空");
        try {
            RoutedCompletion completion = modelRouter.complete(
                    TaskType.QUICK_CLASSIFICATION,
                    new ModelRequest(
                            List.of(
                                    ChatMessage.system(SYSTEM_INSTRUCTION),
                                    ChatMessage.user(capture.sourceText())),
                            List.of(),
                            null,
                            0.0));
            ChatMessage message = completion.response().choices().getFirst().message();
            if (!(message.content() instanceof ChatMessage.TextContent textContent)) {
                throw new IllegalArgumentException("记忆提取模型响应 content 必须是 TextContent");
            }
            return parse(textContent.text());
        } catch (MemoryExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new MemoryExtractionException("记忆提取失败", exception);
        }
    }

    private List<MemoryDraft> parse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        requireObject(root, "根节点必须是 JSON 对象");
        requireExactFields(root, List.of("memories"), "根节点");
        JsonNode memories = root.get("memories");
        if (!memories.isArray()) {
            throw new IllegalArgumentException("memories 必须是数组");
        }
        if (memories.size() > 20) {
            throw new IllegalArgumentException("memories 不能超过 20 项");
        }
        List<MemoryDraft> drafts = new ArrayList<>(memories.size());
        for (JsonNode memory : memories) {
            requireObject(memory, "memory 项必须是 JSON 对象");
            requireExactFields(memory, List.of("type", "title", "content"), "memory 项");
            requireText(memory.get("type"), "type");
            requireText(memory.get("title"), "title");
            requireText(memory.get("content"), "content");
            MemoryType type;
            try {
                type = MemoryType.valueOf(memory.get("type").textValue());
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("未知 memory type", exception);
            }
            drafts.add(new MemoryDraft(
                    type,
                    memory.get("title").textValue(),
                    memory.get("content").textValue()));
        }
        return List.copyOf(drafts);
    }

    private void requireObject(JsonNode node, String message) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireText(JsonNode node, String field) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(field + " 必须是字符串");
        }
    }

    private void requireExactFields(JsonNode node, List<String> fields, String name) {
        List<String> actual = new ArrayList<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            actual.add(names.next());
        }
        if (actual.size() != fields.size() || !actual.containsAll(fields)) {
            throw new IllegalArgumentException(name + " 字段不符合精确协议");
        }
    }
}
