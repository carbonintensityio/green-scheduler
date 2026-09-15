package io.carbonintensity.scheduler;

import java.time.Instant;
import java.util.Optional;

import io.carbonintensity.scheduler.observability.CarbonImpactResult;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * Trigger is bound to a scheduled job.
 * <p>
 * It represents the logic used to test if a scheduled job should be executed
 * at a specific time, i.e., the trigger is "fired".
 *
 * @see GreenScheduled
 */
public interface Trigger {

    /**
     *
     * @return the identifier of the job
     * @see GreenScheduled#identity()
     * @see Scheduler#newJob(String)
     */
    String getId();

    /**
     *
     * @return the next time at which the trigger is scheduled to fire, or {@code null} if it will not fire again
     */
    Instant getNextFireTime();

    /**
     *
     * @return the previous time at which the trigger fired, or {@code null} if it has not fired yet
     */
    Instant getPreviousFireTime();

    /**
     * The grace period is configurable with {@link GreenScheduled#overdueGracePeriod()}.
     * <p>
     * Skipped executions are not considered as overdue.
     *
     * @return {@code false} if the last execution took place between the expected execution time and the end of the grace
     *         period, {@code true} otherwise
     * @see GreenScheduled#overdueGracePeriod()
     */
    boolean isOverdue();

    /**
     *
     * @return the method description or {@code null} for a trigger of a programmatically added job
     */
    default String getMethodDescription() {
        return null;
    }

    /**
     * The most recently computed carbon-impact result for this job, if {@link GreenObserved#carbonImpact()} is
     * enabled for it. Always at least one day old - see {@link GreenObserved#carbonImpact()}.
     *
     * @return the last computed result, or empty if none has been computed yet (or {@code carbonImpact} is disabled)
     * @see GreenObserved
     */
    default Optional<CarbonImpactResult> getLastCarbonImpact() {
        return Optional.empty();
    }

    /**
     * @return the {@link GreenObserved} configuration co-located with this job's {@link GreenScheduled}, or empty if
     *         the job did not opt into observability at all. An extension exports metrics for this job only when
     *         this is present - see {@link GreenObserved}.
     */
    default Optional<GreenObserved> getGreenObserved() {
        return Optional.empty();
    }

    /**
     * @return the {@link GreenScheduled#carbonIntensityZone()} configured for this job, or {@code null} for a
     *         trigger not associated with one (e.g. a purely internal, non-{@link GreenScheduled} trigger)
     */
    default String getCarbonIntensityZone() {
        return null;
    }

    /**
     * @return the scheduling strategy this job is configured with, or {@code null} for a trigger not associated
     *         with one (e.g. a purely internal, non-{@link GreenScheduled} trigger)
     * @see GreenScheduled#fixedWindow()
     * @see GreenScheduled#successive()
     * @see GreenScheduled#cron()
     */
    default Strategy getStrategy() {
        return null;
    }

    /**
     * @return the {@link WindowFireMode} of this job's most recent execution, or empty if it has not fired yet, or
     *         its {@link #getStrategy()} is not {@link Strategy#FIXED_WINDOW}
     */
    default Optional<WindowFireMode> getLastWindowFireMode() {
        return Optional.empty();
    }

    /**
     * The scheduling strategy a {@link GreenScheduled} job is configured with.
     */
    enum Strategy {
        FIXED_WINDOW,
        SUCCESSIVE,
        CRON
    }

    /**
     * Which of the two ways a {@code fixedWindow} job's most recent execution was scheduled: the carbon-aware
     * planner found a slot ({@link #OPTIMIZED}), or it could not, and the job ran on its fallback cron schedule
     * instead ({@link #FALLBACK}).
     * <p>
     * Only meaningful for {@link Strategy#FIXED_WINDOW} - {@link Strategy#SUCCESSIVE} and {@link Strategy#CRON} have
     * no such distinction.
     */
    enum WindowFireMode {
        OPTIMIZED,
        FALLBACK
    }

}
