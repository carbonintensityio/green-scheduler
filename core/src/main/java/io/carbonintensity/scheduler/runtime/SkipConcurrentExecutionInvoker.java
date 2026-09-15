package io.carbonintensity.scheduler.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;

/**
 * An invoker wrapper that skips concurrent executions.
 *
 * @see GreenScheduled#concurrentExecution()
 * @see io.carbonintensity.scheduler.ConcurrentExecution#SKIP
 */
public final class SkipConcurrentExecutionInvoker extends DelegateInvoker {

    private static final Logger log = LoggerFactory.getLogger(SkipConcurrentExecutionInvoker.class);

    private final AtomicBoolean running;
    private final Events events;

    public SkipConcurrentExecutionInvoker(ScheduledInvoker delegate, Events events) {
        super(delegate);
        this.events = events;
        this.running = new AtomicBoolean(false);
    }

    @Override
    public CompletionStage<Void> invoke(ScheduledExecution execution) throws Exception {
        log.trace("Running skip concurrent invoker for {} at {}.", execution.getTrigger().getId(),
                execution.getScheduledFireTime());
        if (running.compareAndSet(false, true)) {
            return invokeDelegate(execution).whenComplete((r, t) -> running.set(false));
        }
        log.debug("Skipped scheduled invoker execution for job '{}' at {}", execution.getTrigger().getId(),
                execution.getScheduledFireTime());
        events.fireJobExecutionSkipped(execution, Scheduler.SkipReason.CONCURRENT_EXECUTION,
                "The scheduled method should not be executed concurrently");
        return CompletableFuture.completedStage(null);
    }

}
