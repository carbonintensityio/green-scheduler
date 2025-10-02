package io.carbonintensity.scheduler;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;

/**
 * A basic scheduler.
 */
public interface Scheduler {

    /**
     * Pause the scheduler. No triggers are fired.
     */
    void pause();

    /**
     * Pause a specific job. Identity must not be null and non-existent identity results in no-op.
     *
     * @param identity
     * @see GreenScheduled#identity()
     */
    void pause(String identity);

    /**
     * Resume the scheduler. Triggers can be fired again.
     */
    void resume();

    /**
     * Resume a specific job. Identity must not be null and non-existent identity results in no-op.
     *
     * @param identity
     * @see GreenScheduled#identity()
     */
    void resume(String identity);

    /**
     * Identity must not be null and {@code false} is returned for non-existent identity.
     * <p>
     * Note that this method only returns {@code true} if the job was explicitly paused. I.e. it does not reflect a paused
     * scheduler.
     *
     * @param identity
     * @return {@code true} if the job with the given identity is paused, {@code false} otherwise
     * @see GreenScheduled#identity()
     * @see #pause(String)
     */
    boolean isPaused(String identity);

    /**
     * @return {@code true} if a scheduler is running the triggers are fired and jobs are executed, {@code false} otherwise
     */
    boolean isRunning();

    /**
     * @return an immutable list of scheduled jobs represented by their trigger.
     */
    List<Trigger> getScheduledJobs();

    /**
     * @return the trigger of a specific job or null for non-existent identity.
     */
    Trigger getScheduledJob(String identity);

    /**
     * Creates a new job definition. The job is not scheduled until the {@link JobDefinition#schedule()} method is called.
     * <p>
     * The properties of the job definition have the same semantics as their equivalents in the {@link GreenScheduled}
     * annotation.
     *
     * @param identity The identity must be unique for the scheduler
     * @return a new job definition
     * @see GreenScheduled#identity()
     */
    JobDefinition newJob(String identity);

    /**
     * Removes the job previously added via {@link #newJob(String)}.
     * <p>
     * It is a no-op if the identified job was not added programmatically.
     *
     * @param identity
     * @return the trigger or {@code null} if no such job exists
     */
    Trigger unscheduleJob(String identity);

    void addJobListener(EventListener listener);

    boolean removeJobListener(EventListener listener);

    interface EventListener {

        default void jobPaused(Trigger trigger) {
        }

        default void jobResumed(Trigger trigger) {
        }

        default void jobExecutionFailed(ScheduledExecution execution, Throwable throwable) {
        }

        default void jobExecutionSkipped(ScheduledExecution execution, String detail) {
        }

        default void jobExecutionSuccessful(ScheduledExecution execution) {
        }

        default void schedulerPaused() {
        }

        default void schedulerResumed() {
        }
    }

    /**
     * The job definition is a builder-like API that can be used to define a job programmatically.
     * <p>
     * No job is scheduled until the {@link #setTask(Consumer)} method is called.
     * <p>
     * The implementation is not thread-safe and should not be reused.
     */
    interface JobDefinition {

        /**
         * Defines minimum gap between the invocations
         * <p>
         * Defaults to 0s
         * <p>
         * Part of {@link GreenScheduled#successive()} ()}
         *
         * @param duration the minimum gap.
         * @return self
         * @see GreenScheduled#successive() ()
         */
        JobDefinition setMinimumGap(Duration duration);

        /**
         * Defines maximum gap between the invocations
         * <p>
         * Defaults to null, no maximum.
         * <p>
         * Part of {@link GreenScheduled#successive()} ()}
         *
         * @param duration the maximum gap
         * @return self
         * @see GreenScheduled#successive() ()
         */
        JobDefinition setMaximumGap(Duration duration);

        /**
         * Defines expected duration of the invocation.
         * <p>
         * {@link GreenScheduled#duration()}
         *
         * @param duration the expected duration of the invocation
         * @return self
         * @see GreenScheduled#duration()
         */
        JobDefinition setDuration(Duration duration);

        /**
         * Defines the carbonIntensityZone for fetching carbon intensity data to use when scheduling.
         * <p>
         * The value are case-insensitive and format depends on the
         * {@link CarbonIntensityPlanner}.
         * <p>
         * The default scheduler supports the following options:
         * <ul>
         * ZoneId from <a href="https://carbonintensity.io">cabonintensity.io</a>; e.g. NL
         *
         * @return self
         * @see GreenScheduled#carbonIntensityZone()
         */
        JobDefinition setCarbonIntensityZone(String carbonIntensityZone);

        /**
         *
         * Specify the strategy to handle concurrent execution of a scheduled method. By default, a scheduled method can be
         * executed concurrently.
         *
         * @param concurrentExecution
         * @return self
         * @see GreenScheduled#concurrentExecution()
         */
        JobDefinition setConcurrentExecution(ConcurrentExecution concurrentExecution);

        /**
         * Specify the predicate that can be used to skip an execution of a scheduled method.
         * <p>
         * The class must declare a public no-args constructor.
         *
         * @param skipPredicate
         * @return self
         * @see GreenScheduled#skipExecutionIf()
         */
        JobDefinition setSkipPredicate(SkipPredicate skipPredicate);

        /**
         * Defines a period after which the job is considered overdue.
         *
         * @param period
         * @return self
         * @see GreenScheduled#overdueGracePeriod()
         */
        JobDefinition setOverdueGracePeriod(Duration period);

        /**
         * @param task
         * @return self
         */
        JobDefinition setTask(Consumer<ScheduledExecution> task);

        /**
         * Attempts to schedule the job.
         *
         * @return the trigger
         */
        Trigger schedule();
    }
}
