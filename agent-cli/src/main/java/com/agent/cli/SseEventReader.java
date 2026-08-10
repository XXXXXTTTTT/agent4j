package com.agent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 解析标准 Server-Sent Events 文本帧。 */
public final class SseEventReader {

    private SseEventReader() {
    }

    /** 读取完整 SSE 文本流；EOF 前未换行的帧也会提交。 */
    public static List<SseEvent> read(Stream<String> lines) {
        Objects.requireNonNull(lines, "lines 不能为空");
        List<SseEvent> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        String id = "";
        String event = "message";
        try (lines) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.isEmpty()) {
                    appendEvent(events, id, event, data);
                    id = "";
                    event = "message";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int separator = line.indexOf(':');
                String field = separator < 0 ? line : line.substring(0, separator);
                String value = separator < 0 ? "" : line.substring(separator + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "id" -> id = value;
                    case "event" -> event = value;
                    case "data" -> {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                    default -> {
                        // 忽略 SSE 扩展字段，保持协议兼容。
                    }
                }
            }
        }
        appendEvent(events, id, event, data);
        return List.copyOf(events);
    }

    private static void appendEvent(
            List<SseEvent> events,
            String id,
            String event,
            StringBuilder data) {
        if (data.length() > 0 || !id.isEmpty() || !"message".equals(event)) {
            events.add(new SseEvent(id, event, data.toString()));
        }
    }

    /** 一个完整 SSE 事件。 */
    public record SseEvent(String id, String event, String data) {
    }
}
