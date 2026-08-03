package com.agent.core.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryContextTest {

    @Test
    void validatesMemoryContextRequest() {
        assertThatThrownBy(() -> new MemoryContextRequest(" ", "user", "task", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryContextRequest("repo", " ", "task", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryContextRequest("repo", "user", " ", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryContextRequest("repo", "user", "task", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryContextRequest("repo", "user", "task", 21))
                .isInstanceOf(IllegalArgumentException.class);

        MemoryContextRequest request = new MemoryContextRequest("repo", "user", "task", 20);

        assertThat(request.repositoryId()).isEqualTo("repo");
        assertThat(request.userId()).isEqualTo("user");
        assertThat(request.query()).isEqualTo("task");
        assertThat(request.limit()).isEqualTo(20);
    }

    @Test
    void validatesMemoryContext() {
        assertThatThrownBy(() -> new MemoryContext(null, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MemoryContext("memory", -1))
                .isInstanceOf(IllegalArgumentException.class);

        MemoryContext empty = new MemoryContext("", 0);

        assertThat(empty.prompt()).isEmpty();
        assertThat(empty.entryCount()).isZero();
    }

    @Test
    void exposesSingleRecallOperation() {
        MemoryContextProvider provider = request ->
                new MemoryContext(request.query(), 1);

        MemoryContext context = provider.recall(
                new MemoryContextRequest("repo", "user", "remember", 1));

        assertThat(context).isEqualTo(new MemoryContext("remember", 1));
    }
}
