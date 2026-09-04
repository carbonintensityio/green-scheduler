package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Trigger;

/**
 * Direct unit test for {@link StatusEmitterInvoker} - it had 100% line coverage from other tests
 * routing through it, but PIT (CIIO-338) found that nothing actually verified the events it's
 * responsible for firing: the success event, and the failure path entirely (0% coverage on that
 * branch specifically).
 */
class StatusEmitterInvokerTest {

    private ScheduledExecution execution() {
        Trigger trigger = mock(Trigger.class);
        when(trigger.getId()).thenReturn("test-trigger");
        ScheduledExecution execution = mock(ScheduledExecution.class);
        when(execution.getTrigger()).thenReturn(trigger);
        when(execution.getScheduledFireTime()).thenReturn(Instant.EPOCH);
        return execution;
    }

    @Test
    void firesJobExecutionSuccessfulAndReturnsTheDelegatesResultOnSuccess() {
        Events events = mock(Events.class);
        ScheduledInvoker delegate = execution -> CompletableFuture.completedStage(null);
        StatusEmitterInvoker invoker = new StatusEmitterInvoker(delegate, events);
        ScheduledExecution execution = execution();

        CompletionStage<Void> result = invoker.invoke(execution);

        assertThat(result.toCompletableFuture()).succeedsWithin(1, TimeUnit.SECONDS);
        verify(events).fireJobExecutionSuccessful(execution);
    }

    @Test
    void firesJobExecutionFailedAndPropagatesTheFailureWhenTheDelegateFails() {
        Events events = mock(Events.class);
        RuntimeException boom = new RuntimeException("task failed");
        ScheduledInvoker delegate = execution -> CompletableFuture.failedStage(boom);
        StatusEmitterInvoker invoker = new StatusEmitterInvoker(delegate, events);
        ScheduledExecution execution = execution();

        CompletionStage<Void> result = invoker.invoke(execution);

        assertThat(result.toCompletableFuture())
                .failsWithin(1, TimeUnit.SECONDS)
                .withThrowableThat()
                .withCause(boom);
        verify(events).fireJobExecutionFailed(execution, boom);
    }
}
