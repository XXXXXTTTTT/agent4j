package com.agent.web.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltInWorkflowTemplatesTest {

    @Test
    void loadsBuiltInTemplatesFromTheClasspathResourceAndRejectsUnknownNames() {
        BuiltInWorkflowTemplates templates = new BuiltInWorkflowTemplates();

        assertThat(templates.template("debug")).contains("请调试");
        assertThatThrownBy(() -> templates.template("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未定义内置工作流模板: missing");
    }
}
