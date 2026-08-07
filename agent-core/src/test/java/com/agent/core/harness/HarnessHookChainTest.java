package com.agent.core.harness;

import com.agent.core.engine.AgentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HarnessHookChainTest {

    private static final UUID RUN_ID = UUID.fromString("b9d51d3b-eccd-4277-9b61-13a783acfa71");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void copiesEventMetadataAndExposesExactFields() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("toolName", "bash");
        AgentState state = AgentState.empty().withVariable("key", "value");

        HarnessEvent event = new HarnessEvent(
                RUN_ID,
                "ops",
                HarnessEventType.BEFORE_TOOL,
                OCCURRED_AT,
                state,
                metadata);
        metadata.put("toolName", "changed");

        assertThat(event.runId()).isEqualTo(RUN_ID);
        assertThat(event.nodeName()).isEqualTo("ops");
        assertThat(event.eventType()).isEqualTo(HarnessEventType.BEFORE_TOOL);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.state()).isSameAs(state);
        assertThat(event.metadata()).containsExactlyEntriesOf(Map.of("toolName", "bash"));
        assertThatThrownBy(() -> event.metadata().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invokesHooksInRegistrationOrderAndCopiesHookList() {
        List<String> calls = new ArrayList<>();
        List<HarnessHook> hooks = new ArrayList<>();
        hooks.add(event -> calls.add("first"));
        hooks.add(event -> calls.add("second"));
        HarnessHookChain chain = new HarnessHookChain(hooks, HarnessAuditSink.noop());
        hooks.clear();

        chain.publish(event(HarnessEventType.BEFORE_NODE));

        assertThat(calls).containsExactly("first", "second");
        assertThat(chain.hooks()).hasSize(2);
        assertThatThrownBy(() -> chain.hooks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void auditsNonCriticalFailureAndContinues() {
        IllegalStateException cause = new IllegalStateException("observer unavailable");
        List<String> calls = new ArrayList<>();
        List<HarnessHookException> audit = new ArrayList<>();
        HarnessHook failing = event -> {
            calls.add("failing");
            throw cause;
        };
        HarnessHookChain chain = new HarnessHookChain(
                List.of(failing, event -> calls.add("after")),
                audit::add);

        chain.publish(event(HarnessEventType.AFTER_NODE));

        assertThat(calls).containsExactly("failing", "after");
        assertThat(audit).singleElement().satisfies(failure -> {
            assertThat(failure.hookName()).isEqualTo(failing.getClass().getName());
            assertThat(failure.eventType()).isEqualTo(HarnessEventType.AFTER_NODE);
            assertThat(failure.getCause()).isSameAs(cause);
        });
    }

    @Test
    void propagatesCriticalFailureAndStopsLaterHooks() {
        IllegalArgumentException cause = new IllegalArgumentException("policy denied");
        List<String> calls = new ArrayList<>();
        HarnessHook critical = new HarnessHook() {
            @Override
            public void onEvent(HarnessEvent event) {
                calls.add("critical");
                throw cause;
            }

            @Override
            public boolean critical() {
                return true;
            }
        };
        HarnessHookChain chain = new HarnessHookChain(
                List.of(critical, event -> calls.add("after")),
                HarnessAuditSink.noop());

        assertThatThrownBy(() -> chain.publish(event(HarnessEventType.BEFORE_TOOL)))
                .isInstanceOfSatisfying(HarnessHookException.class, failure -> {
                    assertThat(failure.hookName()).isEqualTo(critical.getClass().getName());
                    assertThat(failure.eventType()).isEqualTo(HarnessEventType.BEFORE_TOOL);
                    assertThat(failure.getCause()).isSameAs(cause);
                });
        assertThat(calls).containsExactly("critical");
    }

    private HarnessEvent event(HarnessEventType type) {
        return new HarnessEvent(
                RUN_ID,
                "planner",
                type,
                OCCURRED_AT,
                AgentState.empty(),
                Map.of());
    }
}
