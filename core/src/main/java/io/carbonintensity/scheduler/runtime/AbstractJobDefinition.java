package io.carbonintensity.scheduler.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

import io.carbonintensity.scheduler.ConcurrentExecution;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler.JobDefinition;
import io.carbonintensity.scheduler.SkipPredicate;

/**
 * Abstract base class for job definitions in the scheduler.
 * <p>
 * This class provides a framework for defining scheduled jobs, including
 * configuration options such as execution frequency, duration, concurrency,
 * and skipping conditions. Implementations of this class must specify the
 * job execution behavior.
 * </p>
 *
 * <p>
 * Once a job is scheduled, its configuration cannot be modified.
 * Any attempts to change its properties after scheduling will result in an
 * {@link IllegalStateException}.
 * </p>
 */
public abstract class AbstractJobDefinition implements JobDefinition {

    protected final String identity;
    protected Duration initialMaximumDelay = SchedulerDefaults.DEFAULT_INITIAL_MAXIMUM_DELAY;
    protected Duration minimumGap;
    protected Duration maximumGap;
    protected Duration duration = SchedulerDefaults.DEFAULT_DURATION;
    protected Duration overdueGracePeriod = SchedulerDefaults.DEFAULT_OVERDUE_GRACE_PERIOD;
    protected String zone = null;
    protected ConcurrentExecution concurrentExecution = SchedulerDefaults.DEFAULT_CONCURRENT_EXECUTION;
    protected SkipPredicate skipPredicate = null;
    protected Consumer<ScheduledExecution> task;

    protected boolean scheduled = false;

    protected AbstractJobDefinition(String identity) {
        this.identity = identity;
    }

    @Override
    public JobDefinition setMinimumGap(Duration duration) {
        checkScheduled();
        this.minimumGap = Objects.requireNonNull(duration);
        if (this.minimumGap.isNegative() || this.minimumGap.isZero()) {
            throw new IllegalArgumentException("Minimum gap must be greater than zero");
        }
        return this;
    }

    @Override
    public JobDefinition setMaximumGap(Duration duration) {
        checkScheduled();
        this.maximumGap = Objects.requireNonNull(duration);
        if (this.maximumGap.isNegative() || this.maximumGap.isZero()) {
            throw new IllegalArgumentException("Maximum gap must be greater than zero");
        }
        return this;
    }

    @Override
    public JobDefinition setDuration(Duration duration) {
        checkScheduled();
        this.duration = Objects.requireNonNull(duration);
        if (this.duration.isNegative() || this.duration.isZero()) {
            throw new IllegalArgumentException("Duration must be greater than zero");
        }
        return this;
    }

    @Override
    public JobDefinition setCarbonIntensityZone(String carbonIntensityZone) {
        checkScheduled();
        this.zone = Objects.requireNonNull(carbonIntensityZone);
        return this;
    }

    @Override
    public JobDefinition setConcurrentExecution(ConcurrentExecution concurrentExecution) {
        checkScheduled();
        this.concurrentExecution = Objects.requireNonNull(concurrentExecution);
        return this;
    }

    @Override
    public JobDefinition setSkipPredicate(SkipPredicate skipPredicate) {
        checkScheduled();
        this.skipPredicate = Objects.requireNonNull(skipPredicate);
        return this;
    }

    @Override
    public JobDefinition setOverdueGracePeriod(Duration period) {
        checkScheduled();
        this.overdueGracePeriod = Objects.requireNonNull(period);
        if (this.overdueGracePeriod.isNegative()) {
            throw new IllegalArgumentException("Overdue grace period must not be negative");
        }
        return this;
    }

    @Override
    public JobDefinition setTask(Consumer<ScheduledExecution> task) {
        checkScheduled();
        this.task = Objects.requireNonNull(task);
        return this;
    }

    protected void checkScheduled() {
        if (scheduled) {
            throw new IllegalStateException("Cannot modify a job that was already scheduled");
        }
    }

}
