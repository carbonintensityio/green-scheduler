package io.carbonintensity.scheduler.micronaut;

import java.time.Duration;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SchedulerDefaults;
import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuration properties for the green scheduler, bound from the {@code green-scheduler.*} namespace.
 */
@ConfigurationProperties(GreenSchedulerConfigurationProperties.PREFIX)
public class GreenSchedulerConfigurationProperties {

    public static final String PREFIX = "green-scheduler";

    private boolean enabled = true;
    private SchedulerConfig.StartMode startMode = SchedulerConfig.StartMode.NORMAL;
    private int jobExecutors = SchedulerDefaults.DEFAULT_NUMBER_OF_JOB_EXECUTORS;
    private Duration overdueGracePeriod = SchedulerDefaults.DEFAULT_OVERDUE_GRACE_PERIOD;
    private Duration shutdownGracePeriod = SchedulerDefaults.DEFAULT_SHUTDOWN_GRACE_PERIOD;
    private String apiUrl = SchedulerDefaults.DEFAULT_API_URL;
    private String apiKey;
    // Left null (rather than defaulted here) when not configured:
    // CarbonIntensityApiConfig.Builder applies its own defaults in that
    // case, the single source of truth for those values.
    /**
     * Maximum number of attempts for a transient carbon-intensity API call,
     * including the first.
     */
    private Integer carbonIntensityRetryMaxAttempts;
    /** Delay before the first retry of a failed carbon-intensity API call. */
    private Duration carbonIntensityRetryInitialBackoff;
    /** Factor each subsequent retry delay is multiplied by. */
    private Double carbonIntensityRetryBackoffMultiplier;
    /**
     * Total wall-clock time a single carbon-intensity API call may spend
     * retrying.
     */
    private Duration carbonIntensityRetryBudget;
    /**
     * Maximum age of a cached carbon-intensity value before it's no longer
     * reused.
     */
    private Duration carbonIntensityStalenessThreshold;
    /**
     * Total wall-clock time the background recovery poller may spend trying
     * to recover.
     */
    private Duration carbonIntensityRecoveryBudget;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public SchedulerConfig.StartMode getStartMode() {
        return startMode;
    }

    public void setStartMode(SchedulerConfig.StartMode startMode) {
        this.startMode = startMode;
    }

    public int getJobExecutors() {
        return jobExecutors;
    }

    public void setJobExecutors(int jobExecutors) {
        this.jobExecutors = jobExecutors;
    }

    public Duration getOverdueGracePeriod() {
        return overdueGracePeriod;
    }

    public void setOverdueGracePeriod(Duration overdueGracePeriod) {
        this.overdueGracePeriod = overdueGracePeriod;
    }

    public Duration getShutdownGracePeriod() {
        return shutdownGracePeriod;
    }

    public void setShutdownGracePeriod(Duration shutdownGracePeriod) {
        this.shutdownGracePeriod = shutdownGracePeriod;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * @return maximum number of attempts for a transient carbon-intensity API
     *         call, including the first
     */
    public Integer getCarbonIntensityRetryMaxAttempts() {
        return carbonIntensityRetryMaxAttempts;
    }

    /**
     * @param carbonIntensityRetryMaxAttempts see
     *        {@link #getCarbonIntensityRetryMaxAttempts()}
     */
    public void setCarbonIntensityRetryMaxAttempts(Integer carbonIntensityRetryMaxAttempts) {
        this.carbonIntensityRetryMaxAttempts = carbonIntensityRetryMaxAttempts;
    }

    /**
     * @return delay before the first retry of a failed carbon-intensity API
     *         call
     */
    public Duration getCarbonIntensityRetryInitialBackoff() {
        return carbonIntensityRetryInitialBackoff;
    }

    /**
     * @param carbonIntensityRetryInitialBackoff see
     *        {@link #getCarbonIntensityRetryInitialBackoff()}
     */
    public void setCarbonIntensityRetryInitialBackoff(Duration carbonIntensityRetryInitialBackoff) {
        this.carbonIntensityRetryInitialBackoff = carbonIntensityRetryInitialBackoff;
    }

    /** @return factor each subsequent retry delay is multiplied by */
    public Double getCarbonIntensityRetryBackoffMultiplier() {
        return carbonIntensityRetryBackoffMultiplier;
    }

    /** See {@link #getCarbonIntensityRetryBackoffMultiplier()}. */
    public void setCarbonIntensityRetryBackoffMultiplier(Double carbonIntensityRetryBackoffMultiplier) {
        this.carbonIntensityRetryBackoffMultiplier = carbonIntensityRetryBackoffMultiplier;
    }

    /**
     * @return total wall-clock time a single carbon-intensity API call may
     *         spend retrying
     */
    public Duration getCarbonIntensityRetryBudget() {
        return carbonIntensityRetryBudget;
    }

    /** See {@link #getCarbonIntensityRetryBudget()}. */
    public void setCarbonIntensityRetryBudget(Duration carbonIntensityRetryBudget) {
        this.carbonIntensityRetryBudget = carbonIntensityRetryBudget;
    }

    /**
     * @return maximum age of a cached carbon-intensity value before it's no
     *         longer reused
     */
    public Duration getCarbonIntensityStalenessThreshold() {
        return carbonIntensityStalenessThreshold;
    }

    /** See {@link #getCarbonIntensityStalenessThreshold()}. */
    public void setCarbonIntensityStalenessThreshold(Duration carbonIntensityStalenessThreshold) {
        this.carbonIntensityStalenessThreshold = carbonIntensityStalenessThreshold;
    }

    /**
     * @return total wall-clock time the background recovery poller may spend
     *         trying to recover
     */
    public Duration getCarbonIntensityRecoveryBudget() {
        return carbonIntensityRecoveryBudget;
    }

    /** See {@link #getCarbonIntensityRecoveryBudget()}. */
    public void setCarbonIntensityRecoveryBudget(Duration carbonIntensityRecoveryBudget) {
        this.carbonIntensityRecoveryBudget = carbonIntensityRecoveryBudget;
    }
}
