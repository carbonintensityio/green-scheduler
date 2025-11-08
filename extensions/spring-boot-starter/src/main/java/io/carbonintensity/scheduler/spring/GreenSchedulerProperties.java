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
    public static final SchedulerConfig.StartMode DEFAULT_START_MODE = SchedulerConfig.StartMode.NORMAL;
    public static final String DEFAULT_API_URL = SchedulerDefaults.DEFAULT_API_URL;
    public static final Boolean DEFAULT_ENABLED = true;

    @ConstructorBinding // Required to generate metadata: https://stackoverflow.com/questions/79231534/how-can-i-use-optional-values-in-spring-boot-configuration-properties
    public GreenSchedulerProperties(Boolean enabled, SchedulerConfig.StartMode startMode, Integer jobExecutors,
            Duration overdueGracePeriod, Duration shutdownGracePeriod, String apiKey, String apiUrl) {
        this.enabled = Objects.requireNonNullElse(enabled, DEFAULT_ENABLED);
        this.startMode = Objects.requireNonNullElse(startMode, DEFAULT_START_MODE);
        this.jobExecutors = Objects.requireNonNullElse(jobExecutors, DEFAULT_NUMBER_OF_JOB_EXECUTORS);
        this.overdueGracePeriod = Objects.requireNonNullElse(overdueGracePeriod, DEFAULT_OVERDUE_GRACE_PERIOD);
        this.shutdownGracePeriod = Objects.requireNonNullElse(shutdownGracePeriod, DEFAULT_SHUTDOWN_GRACE_PERIOD);
        this.apiKey = apiKey;
        this.apiUrl = Objects.requireNonNullElse(apiUrl, DEFAULT_API_URL);
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
}
