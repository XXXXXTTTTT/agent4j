package com.agent.cli;

import java.net.URI;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Agent4J CLI 的严格命令行参数。 */
public record CliArguments(
        Command command,
        Path workspace,
        URI server,
        Path composeFile) {

    private static final URI DEFAULT_SERVER = URI.create("http://localhost:8080");

    /** 校验解析结果。 */
    public CliArguments {
        Objects.requireNonNull(command, "command 不能为空");
        Objects.requireNonNull(workspace, "workspace 不能为空");
        Objects.requireNonNull(server, "server 不能为空");
        Objects.requireNonNull(composeFile, "composeFile 不能为空");
    }

    /** 解析精确命令与选项，不接受缩写或未知参数。 */
    public static CliArguments parse(String[] arguments) {
        Objects.requireNonNull(arguments, "arguments 不能为空");
        if (arguments.length == 0) {
            throw new IllegalArgumentException("缺少命令: chat、serve 或 conversations");
        }
        Command command = Command.from(arguments[0]);
        Path currentDirectory = Path.of(".").toAbsolutePath().normalize();
        Path workspace = currentDirectory;
        URI server = DEFAULT_SERVER;
        Path composeFile = currentDirectory.resolve("docker-compose.local.yml");
        Set<Option> seen = EnumSet.noneOf(Option.class);

        for (int index = 1; index < arguments.length; index += 2) {
            String name = arguments[index];
            if (index + 1 >= arguments.length) {
                throw new IllegalArgumentException("选项缺少值: " + name);
            }
            Option option = Option.from(name);
            if (!command.allowedOptions().contains(option)) {
                throw new IllegalArgumentException(
                        "命令 " + command.text + " 不支持选项: " + name);
            }
            if (!seen.add(option)) {
                throw new IllegalArgumentException("选项重复: " + name);
            }
            String value = requireValue(arguments[index + 1], name);
            switch (option) {
                case WORKSPACE -> workspace = Path.of(value);
                case SERVER -> server = serverUri(value);
                case COMPOSE_FILE -> composeFile = Path.of(value);
            }
        }
        return new CliArguments(command, workspace, server, composeFile);
    }

    private static String requireValue(String value, String option) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("选项值不能为空: " + option);
        }
        return value;
    }

    private static URI serverUri(String value) {
        URI uri = URI.create(value);
        if (!uri.isAbsolute()
                || (!("http".equals(uri.getScheme())) && !("https".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("server 必须是绝对 HTTP URI");
        }
        return uri;
    }

    /** CLI 顶层命令。 */
    public enum Command {
        CHAT("chat", EnumSet.of(Option.WORKSPACE, Option.SERVER)),
        SERVE("serve", EnumSet.of(Option.WORKSPACE, Option.COMPOSE_FILE)),
        CONVERSATIONS("conversations", EnumSet.of(Option.SERVER));

        private final String text;
        private final Set<Option> allowedOptions;

        Command(String text, Set<Option> allowedOptions) {
            this.text = text;
            this.allowedOptions = Set.copyOf(allowedOptions);
        }

        private Set<Option> allowedOptions() {
            return allowedOptions;
        }

        private static Command from(String value) {
            for (Command command : values()) {
                if (command.text.equals(value)) {
                    return command;
                }
            }
            throw new IllegalArgumentException("未知命令: " + value);
        }
    }

    private enum Option {
        WORKSPACE("--workspace"),
        SERVER("--server"),
        COMPOSE_FILE("--compose-file");

        private final String text;

        Option(String text) {
            this.text = text;
        }

        private static Option from(String value) {
            for (Option option : values()) {
                if (option.text.equals(value)) {
                    return option;
                }
            }
            throw new IllegalArgumentException("未知选项: " + value);
        }
    }
}
