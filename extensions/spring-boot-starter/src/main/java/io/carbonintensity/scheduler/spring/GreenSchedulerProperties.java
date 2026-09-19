package io.carbonintensity.scheduler.spring;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SchedulerDefaults;

/**
 * Green Scheduler spring properties can be found here. All properties have default values and can be overridden.
 * Properties can be set in two ways:<br/>
 * 1. Properties can be set in application.yaml or application.properties
 * 2. Exposing GreenSchedulerProperties bean
 */
@Validated
@ConfigurationProperties("green-scheduler")
public class GreenSchedulerProperties {

    public static final Duration DEFAULT_OVERDUE_GRACE_PERIOD = SchedulerDefaults.DEFAULT_OVERDUE_GRACE_PERIOD;
    public static final Duration DEFAULT_SHUTDOWN_GRACE_PERIOD = SchedulerDefaults.DEFAULT_SHUTDOWN_GRACE_PERIOD;
    public static final int DEFAULT_NUMBER_OF_JOB_EXECUTORS = SchedulerDefaults.DEFAULT_NUMBER_OF_JOB_EXECUTORS;
    public static final int DEFAULT_MAX_CONCURRENT_PER_SLOT = SchedulerDefaults.DEFAULT_MAX_CONCURRENT_PER_SLOT;
    public static final SchedulerConfig.StartMode DEFAULT_START_MODE = SchedulerConfig.StartMode.NORMAL;
    public static final String DEFAULT_API_URL = SchedulerDefaults.DEFAULT_API_URL;
    public static final Boolean DEFAULT_ENABLED = true;

    @ConstructorBinding // Required to generate metadata: https://stackoverflow.com/questions/79231534/how-can-i-use-optional-values-in-spring-boot-configuration-properties
    public GreenSchedulerProperties(Boolean enabled, SchedulerConfig.StartMode startMode, Integer jobExecutors,
            Integer maxConcurrentPerSlot, Duration overdueGracePeriod, Duration shutdownGracePeriod, String apiKey,
            String apiUrl, Integer carbonIntensityRetryMaxAttempts, Duration carbonIntensityRetryInitialBackoff,
            Double carbonIntensityRetryBackoffMultiplier, Duration carbonIntensityRetryBudget,
            Duration carbonIntensityStalenessThreshold, Duration carbonIntensityRecoveryBudget) {
        this.enabled = Objects.requireNonNullElse(enabled, DEFAULT_ENABLED);
        this.startMode = Objects.requireNonNullElse(startMode, DEFAULT_START_MODE);
        this.jobExecutors = Objects.requireNonNullElse(jobExecutors, DEFAULT_NUMBER_OF_JOB_EXECUTORS);
        this.maxConcurrentPerSlot = Objects.requireNonNullElse(maxConcurrentPerSlot, DEFAULT_MAX_CONCURRENT_PER_SLOT);
        this.overdueGracePeriod = Objects.requireNonNullElse(overdueGracePeriod, DEFAULT_OVERDUE_GRACE_PERIOD);
        this.shutdownGracePeriod = Objects.requireNonNullElse(shutdownGracePeriod, DEFAULT_SHUTDOWN_GRACE_PERIOD);
        this.apiKey = apiKey;
        this.apiUrl = Objects.requireNonNullElse(apiUrl, DEFAULT_API_URL);
        // Left null (rather than defaulted here) when not configured: CarbonIntensityApiConfig.Builder
        // applies its own CIIO-470 defaults in that case, the single source of truth for those values.
        this.carbonIntensityRetryMaxAttempts = carbonIntensityRetryMaxAttempts;
        this.carbonIntensityRetryInitialBackoff = carbonIntensityRetryInitialBackoff;
        this.carbonIntensityRetryBackoffMultiplier = carbonIntensityRetryBackoffMultiplier;
        this.carbonIntensityRetryBudget = carbonIntensityRetryBudget;
        this.carbonIntensityStalenessThreshold = carbonIntensityStalenessThreshold;
        this.carbonIntensityRecoveryBudget = carbonIntensityRecoveryBudget;
    }

    public GreenSchedulerProperties() {
    }

    /**
     * Whether to enable or disable. Default true.
     */
    private Boolean enabled = DEFAULT_ENABLED;

    /**
     * Scheduler start mode. Default Normal.
     */
    private SchedulerConfig.StartMode startMode = DEFAULT_START_MODE;

    /**
     * Number of job executors. Default 10.
     */
    private Integer jobExecutors = DEFAULT_NUMBER_OF_JOB_EXECUTORS;

    /**
     * Maximum number of jobs allowed to start at the exact same carbon-intensity slot within the same
     * zone. Default 0 (disabled): jobs schedule independently and may land on the same moment. When set
     * to a positive value, jobs beyond this limit for a given zone/slot are spread to the next-best slot
     * instead, but a job's configured window always takes priority over this limit.
     */
    private Integer maxConcurrentPerSlot = DEFAULT_MAX_CONCURRENT_PER_SLOT;

    /**
     * Overdue grace period. Default 30 seconds.
     */
    private Duration overdueGracePeriod = DEFAULT_OVERDUE_GRACE_PERIOD;

    /**
     * Shutdown grace period. Default 30 seconds.
     */
    private Duration shutdownGracePeriod = DEFAULT_SHUTDOWN_GRACE_PERIOD;

    /**
     * CarbonIntensity API key
     */
    private String apiKey = null;

    /**
     * CarbonIntensity API url.
     */
    private String apiUrl = DEFAULT_API_URL;

    /**
     * Total attempts (1 original + retries) for a single carbon-intensity
     * fetch on the scheduler's critical path. Only retried on a transient
     * connectivity error, never on an HTTP error response. Default 3.
     */
    private Integer carbonIntensityRetryMaxAttempts;

    /**
     * Delay before the first carbon-intensity fetch retry; each subsequent
     * retry multiplies this by {@link #carbonIntensityRetryBackoffMultiplier}.
     * Default 300ms.
     */
    private Duration carbonIntensityRetryInitialBackoff;

    /**
     * Multiplier applied to the previous backoff for each subsequent
     * carbon-intensity fetch retry. Default 3.0 (with the default initial
     * backoff: 300ms, then 900ms).
     */
    private Double carbonIntensityRetryBackoffMultiplier;

    /**
     * Hard ceiling on the total time (across all attempts) a single
     * carbon-intensity fetch may take. Default 2 seconds.
     */
    private Duration carbonIntensityRetryBudget;

    /**
     * How old a last-known-good carbon-intensity value per zone may be and
     * still be reused as a genuine carbon-aware decision when the live API
     * is unreachable. Default 4 hours.
     */
    private Duration carbonIntensityStalenessThreshold;

    /**
     * Total wall-clock time the asynchronous, off-critical-path background
     * recovery poller may keep retrying a zone whose live fetch failed,
     * before giving up until the next foreground failure retriggers it. The
     * poller's own poll interval (30 seconds) is an internal,
     * non-configurable constant - only this total budget can be tuned.
     * Default 10 minutes.
     */
    private Duration carbonIntensityRecoveryBudget;

    /**
     * Gets scheduler start mode.
     *
     * @return start mode
     */
    public Optional<SchedulerConfig.StartMode> getStartMode() {
        return Optional.ofNullable(startMode);
    }

    /**
     * Gets the number of concurrent jobs.
     *
     * @return number of available executors
     */
    public Optional<Integer> getJobExecutors() {
        return Optional.ofNullable(jobExecutors);
    }

    /**
     * Gets the maximum number of jobs allowed to start at the exact same carbon-intensity slot.
     *
     * @return max concurrent jobs per slot
     */
    public Optional<Integer> getMaxConcurrentPerSlot() {
        return Optional.ofNullable(maxConcurrentPerSlot);
    }

    /**
     * Gets the overdue grace period.
     *
     * @return overdue period
     */
    public Optional<Duration> getOverdueGracePeriod() {
        return Optional.ofNullable(overdueGracePeriod);
    }

    /**
     * Get shutdown grace period.
     *
     * @return shutdown grace period
     */
    public Optional<Duration> getShutdownGracePeriod() {
        return Optional.ofNullable(shutdownGracePeriod);
    }

    /**
     * Gets enabled value
     *
     * @return optional boolean if scheduler is enabled. Otherwise, false.
     */
    public Optional<Boolean> getEnabled() {
        return Optional.ofNullable(enabled);
    }

    public Optional<String> getApiKey() {
        return Optional.ofNullable(apiKey);
    }

    public Optional<String> getApiUrl() {
        return Optional.ofNullable(apiUrl);
    }

    /** @return the configured max attempts, if set */
    public Optional<Integer> getCarbonIntensityRetryMaxAttempts() {
        return Optional.ofNullable(carbonIntensityRetryMaxAttempts);
    }

    /** @return the configured initial backoff, if set */
    public Optional<Duration> getCarbonIntensityRetryInitialBackoff() {
        return Optional.ofNullable(carbonIntensityRetryInitialBackoff);
    }

    /** @return the configured backoff multiplier, if set */
    public Optional<Double> getCarbonIntensityRetryBackoffMultiplier() {
        return Optional.ofNullable(carbonIntensityRetryBackoffMultiplier);
    }

    /** @return the configured retry budget, if set */
    public Optional<Duration> getCarbonIntensityRetryBudget() {
        return Optional.ofNullable(carbonIntensityRetryBudget);
    }

    /** @return the configured staleness threshold, if set */
    public Optional<Duration> getCarbonIntensityStalenessThreshold() {
        return Optional.ofNullable(carbonIntensityStalenessThreshold);
    }

    /** @return the configured recovery budget, if set */
    public Optional<Duration> getCarbonIntensityRecoveryBudget() {
        return Optional.ofNullable(carbonIntensityRecoveryBudget);
    }
}
