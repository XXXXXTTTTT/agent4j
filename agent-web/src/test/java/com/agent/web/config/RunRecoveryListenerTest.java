package com.agent.web.config;

import com.agent.core.engine.AgentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RunRecoveryListenerTest {

    @Test
    void recoversRunningRunsOnApplicationReady() {
        AgentRunService runService = mock(AgentRunService.class);
        RunRecoveryListener listener = new RunRecoveryListener(runService);

        listener.onApplicationReady(readyEvent());

        verify(runService).recoverRunningRuns();
    }

    @Test
    void propagatesRecoveryFailure() {
        AgentRunService runService = mock(AgentRunService.class);
        IllegalStateException failure = new IllegalStateException("恢复失败");
        doThrow(failure).when(runService).recoverRunningRuns();
        RunRecoveryListener listener = new RunRecoveryListener(runService);

        assertThatThrownBy(() -> listener.onApplicationReady(readyEvent()))
                .isSameAs(failure);
    }

    private ApplicationReadyEvent readyEvent() {
        return new ApplicationReadyEvent(
                new SpringApplication(Object.class),
                new String[0],
                new GenericApplicationContext(),
                Duration.ZERO);
    }
}
