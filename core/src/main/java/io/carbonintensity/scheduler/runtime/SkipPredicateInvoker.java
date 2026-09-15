package io.carbonintensity.scheduler.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.SkipPredicate;

/**
 * A scheduled invoker wrapper that skips the execution if the predicate evaluates to true.
 *
 * @see GreenScheduled#skipExecutionIf()
 */
public final class SkipPredicateInvoker extends DelegateInvoker {

    private static final Logger log = LoggerFactory.getLogger(SkipPredicateInvoker.class);

    private final SkipPredicate predicate;
    private final Events events;

    public SkipPredicateInvoker(ScheduledInvoker delegate, SkipPredicate predicate,
            Events events) {
        super(delegate);
        this.predicate = predicate;
        this.events = events;
    }

    @Override
    public CompletionStage<Void> invoke(ScheduledExecution execution) {
        log.trace("Running skip predicate invoker for {} at {}.", execution.getTrigger().getId(),
                execution.getScheduledFireTime());
        if (predicate.test(execution)) {
            log.debug("Skipped scheduled invoker execution: {}", delegate.getClass().getName());
            events.fireJobExecutionSkipped(execution, Scheduler.SkipReason.SKIP_PREDICATE, predicate.getClass().getName());
            return CompletableFuture.completedStage(null);
        } else {
            return invokeDelegate(execution);
        }
    }

}
