package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Tests for {@link SkipConcurrentExecutionInvoker#invoke(ScheduledExecution)}.
 * <p>
 * The invariant under test is a thread-race one - "over any interleaving of concurrent {@code invoke()} calls, at
 * most one delegate invocation runs at a time (CAS on the {@code running} flag), and after every completion
 * (success or failure) the flag always clears again, so a later call is never permanently skipped" - but the
 * mechanism itself (a single {@link java.util.concurrent.atomic.AtomicBoolean} guarded by
 * {@link java.util.concurrent.atomic.AtomicBoolean#compareAndSet}, reset in a {@code whenComplete} callback) is
 * deterministic once the delegate's {@link CompletionStage} is under test control. Rather than a real
 * multi-threaded stress test, every test here drives a delegate backed by a hand-held {@link CompletableFuture}:
 * it "hangs" until the test completes it, which lets a second/third {@code invoke()} call be interleaved
 * deterministically without any real concurrency.
 */
class SkipConcurrentExecutionInvokerTest {

    private static final String SKIPPED_DETAIL = "The scheduled method should not be executed concurrently";

    @Test
    void concurrentInvocationIsSkippedWhileFirstIsPending() throws Exception {
        AtomicInteger skipCount = new AtomicInteger();
        Events events = eventsCapturingSkips(skipCount);
        ControllableInvoker delegate = new ControllableInvoker();
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, events);
        ScheduledExecution execution = new FixedExecution();

        invoker.invoke(execution);
        assertThat(delegate.invocationCount()).isEqualTo(1);

        // Second call arrives while the first delegate invocation is still pending: it must be skipped, not
        // forwarded to the delegate.
        CompletionStage<Void> skipped = invoker.invoke(execution);

        assertThat(delegate.invocationCount()).isEqualTo(1);
        assertThat(skipCount.get()).isEqualTo(1);
        assertThat(skipped.toCompletableFuture()).isCompletedWithValue(null);
    }

    @Test
    void skippedInvocationFiresSkipEventWithOriginalExecution() throws Exception {
        AtomicInteger skipCount = new AtomicInteger();
        ScheduledExecution[] skippedExecution = new ScheduledExecution[1];
        String[] skippedDetail = new String[1];
        Events events = events(List.of(new Scheduler.EventListener() {
            @Override
            public void jobExecutionSkipped(ScheduledExecution execution, String detail) {
                skipCount.incrementAndGet();
                skippedExecution[0] = execution;
                skippedDetail[0] = detail;
            }
        }));
        ControllableInvoker delegate = new ControllableInvoker();
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, events);
        ScheduledExecution execution = new FixedExecution();

        invoker.invoke(execution);
        invoker.invoke(execution);

        assertThat(skipCount.get()).isEqualTo(1);
        assertThat(skippedExecution[0]).isSameAs(execution);
        assertThat(skippedDetail[0]).isEqualTo(SKIPPED_DETAIL);
    }

    @Test
    void flagIsClearedAfterSuccessfulCompletionSoNextInvocationProceeds() throws Exception {
        Events events = eventsCapturingSkips(new AtomicInteger());
        ControllableInvoker delegate = new ControllableInvoker();
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, events);
        ScheduledExecution execution = new FixedExecution();

        invoker.invoke(execution);
        invoker.invoke(execution); // skipped while pending
        assertThat(delegate.invocationCount()).isEqualTo(1);

        delegate.mostRecentFuture().complete(null);

        invoker.invoke(execution);
        assertThat(delegate.invocationCount()).isEqualTo(2);
    }

    @Test
    void flagIsClearedAfterExceptionalCompletionSoNextInvocationProceeds() throws Exception {
        Events events = eventsCapturingSkips(new AtomicInteger());
        ControllableInvoker delegate = new ControllableInvoker();
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, events);
        ScheduledExecution execution = new FixedExecution();

        invoker.invoke(execution);
        invoker.invoke(execution); // skipped while pending
        assertThat(delegate.invocationCount()).isEqualTo(1);

        delegate.mostRecentFuture().completeExceptionally(new RuntimeException("boom"));

        invoker.invoke(execution);
        assertThat(delegate.invocationCount()).isEqualTo(2);
    }

    @Test
    void flagIsClearedAfterSynchronousExceptionFromDelegate() throws Exception {
        // The delegate throws directly, rather than returning a failed CompletionStage. DelegateInvoker#invokeDelegate
        // catches this and converts it into an already-failed stage, so the whenComplete(...) that resets the
        // "running" flag still gets attached (and, since the stage is already complete, runs synchronously) - the
        // flag must not leak "stuck" in this path.
        Events events = eventsCapturingSkips(new AtomicInteger());
        ControllableInvoker delegate = new ControllableInvoker();
        delegate.throwOnNextInvocation(new IllegalStateException("boom"));
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, events);
        ScheduledExecution execution = new FixedExecution();

        CompletionStage<Void> result = invoker.invoke(execution);

        assertThat(result.toCompletableFuture()).isCompletedExceptionally();
        assertThat(delegate.invocationCount()).isEqualTo(1);

        // The flag must already be clear again, immediately - no separate "unstick" step should be needed.
        invoker.invoke(execution);
        assertThat(delegate.invocationCount()).isEqualTo(2);
    }

    /**
     * Property-based complement to the example-based tests above: for any number of overlapping calls made while
     * the first delegate invocation is still pending, and for either outcome (success or failure) it eventually
     * completes with, the invariant holds - exactly one delegate invocation runs at a time, every overlapping call
     * is skipped, and the flag is clear again afterwards so a further call proceeds. The actual interleaving stays
     * deterministic/imperative inside each check; only the shape of the scenario is varied, so no real threads are
     * needed.
     * <p>
     * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
     * jqwik is used here.
     */
    @Test
    void invariantHoldsForAnyNumberOfOverlappingCallsAndEitherOutcome() {
        Arbitrary<Integer> overlappingCalls = Gen.choose(1, 5).arbitrary();
        Arbitrary<Boolean> completesSuccessfully = Arbitrary.of(true, false);

        Property.def("at most one delegate invocation runs concurrently, and the flag always clears afterwards")
                .forAll(overlappingCalls, completesSuccessfully)
                .suchThat((extraCalls, successfully) -> {
                    try {
                        return checkInvariant(extraCalls, successfully);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .check()
                .assertIsSatisfied();
    }

    private boolean checkInvariant(int extraOverlappingCalls, boolean completesSuccessfully) throws Exception {
        AtomicInteger skipCount = new AtomicInteger();
        Events events = eventsCapturingSkips(skipCount);
        ControllableInvoker delegate = new ControllableInvoker();
        SkipConcurrentExecutionInvoker invoker = new SkipConcurrentExecutionInvoker(delegate, events);
        ScheduledExecution execution = new FixedExecution();

        invoker.invoke(execution);
        for (int i = 0; i < extraOverlappingCalls; i++) {
            invoker.invoke(execution);
        }

        boolean onlyOneRanConcurrently = delegate.invocationCount() == 1;
        boolean everyOverlappingCallWasSkipped = skipCount.get() == extraOverlappingCalls;

        if (completesSuccessfully) {
            delegate.mostRecentFuture().complete(null);
        } else {
            delegate.mostRecentFuture().completeExceptionally(new RuntimeException("boom"));
        }

        invoker.invoke(execution);
        boolean flagWasClearedAfterCompletion = delegate.invocationCount() == 2;

        return onlyOneRanConcurrently && everyOverlappingCallWasSkipped && flagWasClearedAfterCompletion;
    }

    private static Events eventsCapturingSkips(AtomicInteger skipCount) {
        return events(List.of(new Scheduler.EventListener() {
            @Override
            public void jobExecutionSkipped(ScheduledExecution execution, String detail) {
                skipCount.incrementAndGet();
            }
        }));
    }

    private static Events events(List<Scheduler.EventListener> listeners) {
        SimpleScheduler simpleScheduler = mock(SimpleScheduler.class);
        when(simpleScheduler.getEventListeners()).thenReturn(listeners);
        return new Events(simpleScheduler);
    }

    /**
     * A {@link ScheduledInvoker} whose {@link CompletionStage} the test holds onto and completes by hand, so a
     * concurrent invocation can be deterministically interleaved while the first is still "running".
     */
    private static final class ControllableInvoker implements ScheduledInvoker {

        private final AtomicInteger invocationCount = new AtomicInteger();
        private final Deque<CompletableFuture<Void>> pendingFutures = new ArrayDeque<>();
        private RuntimeException nextSynchronousException;

        @Override
        public CompletionStage<Void> invoke(ScheduledExecution execution) {
            invocationCount.incrementAndGet();
            if (nextSynchronousException != null) {
                RuntimeException toThrow = nextSynchronousException;
                nextSynchronousException = null;
                throw toThrow;
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            pendingFutures.addLast(future);
            return future;
        }

        void throwOnNextInvocation(RuntimeException exception) {
            this.nextSynchronousException = exception;
        }

        int invocationCount() {
            return invocationCount.get();
        }

        CompletableFuture<Void> mostRecentFuture() {
            return pendingFutures.getLast();
        }
    }

    private static final class FixedTrigger implements Trigger {
        @Override
        public String getId() {
            return "test-job";
        }

        @Override
        public Instant getNextFireTime() {
            return null;
        }

        @Override
        public Instant getPreviousFireTime() {
            return null;
        }

        @Override
        public boolean isOverdue() {
            return false;
        }
    }

    private static final class FixedExecution implements ScheduledExecution {
        private final Trigger trigger = new FixedTrigger();

        @Override
        public Trigger getTrigger() {
            return trigger;
        }

        @Override
        public Instant getFireTime() {
            return Instant.EPOCH;
        }

        @Override
        public Instant getScheduledFireTime() {
            return Instant.EPOCH;
        }
    }
}
