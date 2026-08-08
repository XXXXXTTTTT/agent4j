package com.agent.core.cli;

import java.util.regex.Pattern;

/** CLI 领域对象共享的精确格式校验。 */
final class CliValidation {

    private static final Pattern DEFINITION_NAME = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final int MAX_TOKEN_CODE_POINTS = 4096;
    private static final String SHELL_CONTROL_CHARACTERS = ";&|<>`$";

    private CliValidation() {
    }

    static boolean isDefinitionName(String value) {
        return value != null && DEFINITION_NAME.matcher(value).matches();
    }

    static void validateDefinitionName(String value) {
        if (!isDefinitionName(value)) {
            throw new CliCommandDefinitionException(value, "命令名格式非法");
        }
    }

    static void validateExecutable(String commandName, String value) {
        if (value == null || value.isBlank() || value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new CliCommandDefinitionException(commandName, "executable 必须是单个非空 token");
        }
        validateDefinitionToken(commandName, value, -1, "executable");
    }

    static void validateDefinitionArgument(String commandName, String value, int index) {
        validateDefinitionToken(commandName, value, index, "固定参数");
    }

    static void validateIntentArgument(String commandName, String value, int index) {
        String violation = tokenViolation(value);
        if (violation != null) {
            throw new CliArgumentException(commandName, index, "参数非法: " + violation);
        }
    }

    private static void validateDefinitionToken(
            String commandName,
            String value,
            int index,
            String field) {
        String violation = tokenViolation(value);
        if (violation != null) {
            throw new CliCommandDefinitionException(
                    commandName,
                    field + "非法，index=" + index + ": " + violation);
        }
    }

    private static String tokenViolation(String value) {
        if (value == null || value.isEmpty()) {
            return "token 不能为空";
        }
        if (value.codePointCount(0, value.length()) > MAX_TOKEN_CODE_POINTS) {
            return "token 不能超过 4096 个 code point";
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                return "token 包含控制字符";
            }
            if (SHELL_CONTROL_CHARACTERS.indexOf(codePoint) >= 0) {
                return "token 包含 Shell 控制字符";
            }
            offset += Character.charCount(codePoint);
        }
        return null;
    }
}
