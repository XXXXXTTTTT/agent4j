package com.agent.core.command;

import java.util.ArrayList;
import java.util.List;

/** 解析以斜杠开头的命令，不执行名称模糊匹配或格式归一化。 */
public final class SlashCommandParser {

    /** 将原始输入解析为精确命令名称和参数列表。 */
    public CommandInvocation parse(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new CommandParseException("命令输入不能为空");
        }
        String input = rawInput.strip();
        if (!input.startsWith("/")) {
            throw new CommandParseException("命令必须以 / 开头");
        }
        List<String> tokens = tokenize(input.substring(1), rawInput);
        if (tokens.isEmpty() || tokens.getFirst().isBlank()) {
            throw new CommandParseException("命令名称不能为空");
        }
        return new CommandInvocation(tokens.getFirst(), tokens.subList(1, tokens.size()), rawInput);
    }

    private List<String> tokenize(String input, String rawInput) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        boolean tokenStarted = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                tokenStarted = true;
                continue;
            }
            if (character == '\\' && quoted) {
                escaped = true;
                tokenStarted = true;
                continue;
            }
            if (character == '"') {
                quoted = !quoted;
                tokenStarted = true;
                continue;
            }
            if (Character.isWhitespace(character) && !quoted) {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                continue;
            }
            current.append(character);
            tokenStarted = true;
        }
        if (escaped || quoted) {
            throw new CommandParseException("命令引号或转义未闭合: " + rawInput);
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return List.copyOf(tokens);
    }
}
