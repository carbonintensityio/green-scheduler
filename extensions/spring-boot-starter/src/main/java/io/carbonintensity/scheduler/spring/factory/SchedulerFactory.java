package io.carbonintensity.scheduler.spring.factory;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;

/**
 * Interface for {@link Scheduler} factories.
 */
public interface SchedulerFactory {

    /**
     * Create a new scheduler.
     *
     * @param schedulerConfig scheduler config
     * @return new instance of {@link Scheduler}.
     */
    Scheduler createScheduler(SchedulerConfig schedulerConfig);
}
