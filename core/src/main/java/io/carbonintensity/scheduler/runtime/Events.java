package io.carbonintensity.scheduler.runtime;

import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.CarbonImpactResult;

/**
 * Utility class for firing events related to job execution and scheduler state changes.
 * <p>
 * This class provides methods to fire various events related to the execution and status of scheduled jobs.
 * These events notify {@link Scheduler.EventListener} implementations about job execution successes, failures,
 * pauses, resumes, and other state transitions.
 * </p>
 *
 * <p>
 * The events fired by this class include:
 * <ul>
 * <li>Job execution successful</li>
 * <li>Job execution failed</li>
 * <li>Scheduler paused</li>
 * <li>Scheduler resumed</li>
 * <li>Job paused</li>
 * <li>Job resumed</li>
 * <li>Job execution skipped</li>
 * </ul>
 * </p>
 *
 * <p>
 * All events are broadcast to all registered {@link Scheduler.EventListener}s via the
 * {@link SimpleScheduler}'s listener collection.
 * </p>
 *
 * @see Scheduler
 * @see Scheduler.EventListener
 * @see ScheduledExecution
 * @see Trigger
 * @see SimpleScheduler
 */
public final class Events {

    private final SimpleScheduler simpleScheduler;

    Events(SimpleScheduler simpleScheduler) {
        this.simpleScheduler = simpleScheduler;
    }

    void fireJobExecutionSuccessful(ScheduledExecution execution) {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.jobExecutionSuccessful(execution);
        }
    }

    void fireJobExecutionFailed(ScheduledExecution execution, Throwable t) {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.jobExecutionFailed(execution, t);
        }
    }

    void fireSchedulerPaused() {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.schedulerPaused();
        }
    }

    void fireSchedulerResumed() {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.schedulerResumed();
        }
    }

    void fireJobPaused(Trigger trigger) {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.jobPaused(trigger);
        }
    }

    void fireJobResumed(Trigger trigger) {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.jobResumed(trigger);
        }
    }

    void fireJobExecutionSkipped(ScheduledExecution execution, Scheduler.SkipReason reason, String details) {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.jobExecutionSkipped(execution, reason, details);
        }
    }

    void fireJobCarbonImpactCalculated(Trigger trigger, CarbonImpactResult result) {
        for (Scheduler.EventListener listener : simpleScheduler.getEventListeners()) {
            listener.jobCarbonImpactCalculated(trigger, result);
        }
    }
}
