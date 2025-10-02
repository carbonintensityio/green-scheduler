package io.carbonintensity.scheduler.runtime;

import java.time.Duration;

import io.carbonintensity.scheduler.ConcurrentExecution;

/**
 * Default configuration of a scheduler
 */
public final class SchedulerDefaults {

    public static final Duration DEFAULT_INITIAL_MAXIMUM_DELAY = Duration.ofSeconds(0);
    public static final Duration DEFAULT_SHUTDOWN_GRACE_PERIOD = Duration.ofSeconds(30);
    public static final Duration DEFAULT_OVERDUE_GRACE_PERIOD = Duration.ofSeconds(30);
    public static final ConcurrentExecution DEFAULT_CONCURRENT_EXECUTION = ConcurrentExecution.PROCEED;
    public static final Duration DEFAULT_DURATION = Duration.ofSeconds(1);
    public static final String DEFAULT_API_URL = "https://api.carbonintensity.io";
    public static final int DEFAULT_NUMBER_OF_JOB_EXECUTORS = 10;

    private SchedulerDefaults() {
    }
}
