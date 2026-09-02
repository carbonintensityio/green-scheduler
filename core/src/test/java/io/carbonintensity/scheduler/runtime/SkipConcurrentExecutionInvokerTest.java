package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Trigger;

/**
 * Direct unit test for {@link SkipConcurrentExecutionInvoker}, not through the full scheduler -
 * {@code TestFallbackProgrammatic#testConcurrentSkip} already verifies the skip *behavior*
 * (a concurrent call fires a skip event) via that route, but never inspects what {@link #invoke}
 * actually returns, nor whether {@code running} resets afterwards. Both surfaced as surviving PIT
 * mutants (CIIO-335) despite 100% line coverage.
 */
class SkipConcurrentExecutionInvokerTest {

    private ScheduledExecution execution() {
        Trigger trigger = mock(Trigger.class);
        when(trigger.getId()).thenReturn("test-trigger");
        ScheduledExecution execution = mock(ScheduledExecution.class);
        when(execution.getTrigger()).thenReturn(trigger);
        when(execution.getScheduledFireTime()).thenReturn(Instant.EPOCH);
        return execution;
    }

    @Test
    void invokeReturnsTheDelegatesResultAndResetsAfterCompletion() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();
        ScheduledInvoker delegate = execution -> {
            delegateCalls.incrementAndGet();
            return CompletableFuture.completedStage(null);
        };
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, mock(Events.class));

        CompletionStage<Void> first = invoker.invoke(execution());
        // Kills the "replaced return value with null" mutant on the success path: a caller awaiting
        // this stage must actually see it complete, not receive a stage that never does.
        assertThat(first.toCompletableFuture()).succeedsWithin(1, TimeUnit.SECONDS);
        assertThat(delegateCalls).hasValue(1);

        // Kills the "removed call to AtomicBoolean::set" mutant: without the reset, this second,
        // non-concurrent call would be skipped forever instead of reaching the delegate.
        CompletionStage<Void> second = invoker.invoke(execution());
        assertThat(second.toCompletableFuture()).succeedsWithin(1, TimeUnit.SECONDS);
        assertThat(delegateCalls).hasValue(2);
    }

    @Test
    void invokeReturnsACompletedStageWhenSkippingAConcurrentCall() throws Exception {
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        CountDownLatch delegateStarted = new CountDownLatch(1);
        ScheduledInvoker delegate = execution -> {
            delegateStarted.countDown();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    releaseDelegate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return (Void) null;
            });
        };
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, mock(Events.class));

        CompletionStage<Void> firstStillRunning = invoker.invoke(execution());
        assertThat(delegateStarted.await(1, TimeUnit.SECONDS)).isTrue();

        // Kills the "replaced return value with null" mutant on the skip path: the returned stage
        // must actually be a completed one (so a caller doesn't wait on it forever), not merely non-null.
        CompletionStage<Void> skipped = invoker.invoke(execution());
        assertThat(skipped.toCompletableFuture()).isCompleted();

        releaseDelegate.countDown();
        assertThat(firstStillRunning.toCompletableFuture()).succeedsWithin(1, TimeUnit.SECONDS);
    }
}
