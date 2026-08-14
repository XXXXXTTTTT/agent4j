package com.agent.core.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlashCommandParserTest {

    @Test
    void parsesExactNameAndQuotedArgumentsWithoutNormalizingTheName() {
        CommandInvocation invocation = new SlashCommandParser().parse(
                "/review \"security pass\" --fix");

        assertThat(invocation.name()).isEqualTo("review");
        assertThat(invocation.arguments()).containsExactly("security pass", "--fix");
    }

    @Test
    void rejectsEmptyCommandMissingSlashAndUnterminatedQuote() {
        SlashCommandParser parser = new SlashCommandParser();

        assertThatThrownBy(() -> parser.parse("/"))
                .isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parser.parse("review"))
                .isInstanceOf(CommandParseException.class);
        assertThatThrownBy(() -> parser.parse("/review \"broken"))
                .isInstanceOf(CommandParseException.class);
    }
}
