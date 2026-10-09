package io.carbonintensity.scheduler.quarkus.factory;

import java.time.Duration;

import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiConfig;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SchedulerDefaults;

/**
 * {@link SchedulerConfig} builder.
 */
public class SchedulerConfigBuilder {

    public static final Duration DEFAULT_OVERDUE_GRACE_PERIOD = SchedulerDefaults.DEFAULT_OVERDUE_GRACE_PERIOD;
    public static final Duration DEFAULT_SHUTDOWN_GRACE_PERIOD = SchedulerDefaults.DEFAULT_SHUTDOWN_GRACE_PERIOD;
    public static final int DEFAULT_NUMBER_OF_JOB_EXECUTORS = SchedulerDefaults.DEFAULT_NUMBER_OF_JOB_EXECUTORS;
    public static final int DEFAULT_MAX_CONCURRENT_PER_SLOT = SchedulerDefaults.DEFAULT_MAX_CONCURRENT_PER_SLOT;
    public static final SchedulerConfig.StartMode DEFAULT_START_MODE = SchedulerConfig.StartMode.NORMAL;
    public static final String DEFAULT_API_URL = SchedulerDefaults.DEFAULT_API_URL;
    public static final Boolean DEFAULT_ENABLED = true;

    private boolean enabled;
    private SchedulerConfig.StartMode startMode;
    private Integer jobExecutorCount;
    private Integer maxConcurrentPerSlot;
    private Duration shutdownGracePeriod;
    private Duration overdueGracePeriod;
    private String apiKey;
    private String apiUrl;
    private CarbonIntensityApi carbonIntensityApi;
    /**
     * Maximum number of attempts for a transient carbon-intensity API call,
     * including the first.
     */
    private Integer retryMaxAttempts;
    /** Delay before the first retry of a failed carbon-intensity API call. */
    private Duration retryInitialBackoff;
    /** Factor each subsequent retry delay is multiplied by. */
    private Double retryBackoffMultiplier;
    /**
     * Total wall-clock time a single carbon-intensity API call may spend
     * retrying.
     */
    private Duration retryBudget;
    /**
     * Maximum age of a cached carbon-intensity value before it's no longer
     * reused.
     */
    private Duration stalenessThreshold;
    /**
     * Total wall-clock time the background recovery poller may spend trying
     * to recover.
     */
    private Duration recoveryBudget;

    /**
     * Constructor for pre-populating with properties
     *
     * @param greenSchedulerProperties starter properties
     */
    public SchedulerConfigBuilder(GreenSchedulerProperties greenSchedulerProperties) {
        populateByProperties(greenSchedulerProperties);
    }

    private void populateByProperties(GreenSchedulerProperties properties) {
        enabled(properties.enabled().orElse(DEFAULT_ENABLED));
        startMode(properties.startMode().orElse(DEFAULT_START_MODE));
        jobExecutorCount(properties.jobExecutors().orElse(DEFAULT_NUMBER_OF_JOB_EXECUTORS));
        maxConcurrentPerSlot(properties.maxConcurrentPerSlot().orElse(DEFAULT_MAX_CONCURRENT_PER_SLOT));
        overdueGracePeriod(properties.overdueGracePeriod().orElse(DEFAULT_OVERDUE_GRACE_PERIOD));
        shutdownGracePeriod(properties.shutdownGracePeriod().orElse(DEFAULT_SHUTDOWN_GRACE_PERIOD));
        apiUrl(properties.apiUrl().orElse(DEFAULT_API_URL));
        properties.apiKey().ifPresent(this::apiKey);
        properties.carbonIntensityRetryMaxAttempts().ifPresent(this::retryMaxAttempts);
        properties.carbonIntensityRetryInitialBackoff().ifPresent(this::retryInitialBackoff);
        properties.carbonIntensityRetryBackoffMultiplier().ifPresent(this::retryBackoffMultiplier);
        properties.carbonIntensityRetryBudget().ifPresent(this::retryBudget);
        properties.carbonIntensityStalenessThreshold().ifPresent(this::stalenessThreshold);
        properties.carbonIntensityRecoveryBudget().ifPresent(this::recoveryBudget);
    }

    public SchedulerConfigBuilder startMode(SchedulerConfig.StartMode startMode) {
        Assert.notNull(startMode, "startMode cannot be null");
        this.startMode = startMode;
        return this;
    }

    public SchedulerConfigBuilder jobExecutorCount(Integer jobExecutors) {
        Assert.notNull(jobExecutors, "jobExecutors cannot be null");
        Assert.isTrue(jobExecutors > 0, "jobExecutors must be greater than 0");
        this.jobExecutorCount = jobExecutors;
        return this;
    }

    public SchedulerConfigBuilder maxConcurrentPerSlot(Integer maxConcurrentPerSlot) {
        Assert.notNull(maxConcurrentPerSlot, "maxConcurrentPerSlot cannot be null");
        Assert.isTrue(maxConcurrentPerSlot >= 0, "maxConcurrentPerSlot must be greater than or equal to 0");
        this.maxConcurrentPerSlot = maxConcurrentPerSlot;
        return this;
    }

    public SchedulerConfigBuilder apiKey(String apiKey) {
        Assert.hasText(apiKey, "apiKey cannot be null");
        this.apiKey = apiKey;
        return this;
    }

    public SchedulerConfigBuilder apiUrl(String apiUrl) {
        Assert.hasText(apiUrl, "apiUrl cannot be null");
        this.apiUrl = apiUrl;
        return this;
    }

    public SchedulerConfigBuilder overdueGracePeriod(Duration overdueGracePeriod) {
        Assert.notNull(overdueGracePeriod, "overdueGracePeriod cannot be null");
        Assert.isTrue(overdueGracePeriod.toHours() < 24, "overdueGracePeriod must be less than 24 hours");
        Assert.isTrue(overdueGracePeriod.toSeconds() > -1, "overdueGracePeriod must be greater than -1 seconds");
        this.overdueGracePeriod = overdueGracePeriod;
        return this;
    }

    public SchedulerConfigBuilder shutdownGracePeriod(Duration shutdownGracePeriod) {
        Assert.notNull(shutdownGracePeriod, "shutdownGracePeriod cannot be null");
        Assert.isTrue(shutdownGracePeriod.toHours() < 24, "shutdownGracePeriod must be less than 24 hours");
        Assert.isTrue(shutdownGracePeriod.toSeconds() > -1, "shutdownGracePeriod must be greater than -1 seconds");
        this.shutdownGracePeriod = shutdownGracePeriod;
        return this;
    }

    public SchedulerConfigBuilder enabled(Boolean enabled) {
        Assert.notNull(enabled, "enabled cannot be null");
        this.enabled = enabled;
        return this;
    }

    public SchedulerConfigBuilder enabled() {
        return enabled(true);
    }

    public SchedulerConfigBuilder disabled() {
        return enabled(false);
    }

    public SchedulerConfigBuilder carbonIntensityApi(CarbonIntensityApi carbonIntensityApi) {
        this.carbonIntensityApi = carbonIntensityApi;
        return this;
    }

    /**
     * @param maxAttempts maximum number of attempts for a transient
     *        carbon-intensity API call, including the first
     * @return this builder
     */
    public SchedulerConfigBuilder retryMaxAttempts(Integer maxAttempts) {
        Assert.notNull(maxAttempts, "retryMaxAttempts cannot be null");
        Assert.isTrue(maxAttempts >= 1, "retryMaxAttempts must be at least 1");
        this.retryMaxAttempts = maxAttempts;
        return this;
    }

    /**
     * @param initialBackoff delay before the first retry of a failed
     *        carbon-intensity API call
     * @return this builder
     */
    public SchedulerConfigBuilder retryInitialBackoff(Duration initialBackoff) {
        Assert.notNull(initialBackoff, "retryInitialBackoff cannot be null");
        this.retryInitialBackoff = initialBackoff;
        return this;
    }

    /**
     * @param backoffMultiplier factor each subsequent retry delay is
     *        multiplied by
     * @return this builder
     */
    public SchedulerConfigBuilder retryBackoffMultiplier(Double backoffMultiplier) {
        Assert.notNull(backoffMultiplier, "retryBackoffMultiplier cannot be null");
        Assert.isTrue(backoffMultiplier >= 1.0, "retryBackoffMultiplier must be at least 1.0");
        this.retryBackoffMultiplier = backoffMultiplier;
        return this;
    }

    /**
     * @param budget total wall-clock time a single carbon-intensity API call
     *        may spend retrying
     * @return this builder
     */
    public SchedulerConfigBuilder retryBudget(Duration budget) {
        Assert.notNull(budget, "retryBudget cannot be null");
        this.retryBudget = budget;
        return this;
    }

    /**
     * @param threshold maximum age of a cached carbon-intensity value before
     *        it's no longer reused
     * @return this builder
     */
    public SchedulerConfigBuilder stalenessThreshold(Duration threshold) {
        Assert.notNull(threshold, "stalenessThreshold cannot be null");
        this.stalenessThreshold = threshold;
        return this;
    }

    /**
     * @param budget total wall-clock time the background recovery poller may
     *        spend trying to recover
     * @return this builder
     */
    public SchedulerConfigBuilder recoveryBudget(Duration budget) {
        Assert.notNull(budget, "recoveryBudget cannot be null");
        Assert.isTrue(!budget.isZero() && !budget.isNegative(), "recoveryBudget must be positive");
        this.recoveryBudget = budget;
        return this;
    }

    public SchedulerConfig build() {
        var schedulerConfig = new SchedulerConfig();
        schedulerConfig.setEnabled(enabled);
        schedulerConfig.setStartMode(startMode);
        schedulerConfig.setOverdueGracePeriod(overdueGracePeriod);
        schedulerConfig.setShutdownGracePeriod(shutdownGracePeriod);
        schedulerConfig.setJobExecutors(jobExecutorCount);
        schedulerConfig.setMaxConcurrentPerSlot(maxConcurrentPerSlot);

        if (this.carbonIntensityApi != null) {
            schedulerConfig.setCarbonIntensityApi(carbonIntensityApi);
        } else {
            schedulerConfig.setCarbonIntensityApiConfig(
                    new CarbonIntensityApiConfig.Builder()
                            .apiKey(apiKey)
                            .apiUrl(apiUrl)
                            .retryMaxAttempts(retryMaxAttempts)
                            .retryInitialBackoff(retryInitialBackoff)
                            .retryBackoffMultiplier(retryBackoffMultiplier)
                            .retryBudget(retryBudget)
                            .stalenessThreshold(stalenessThreshold)
                            .recoveryBudget(recoveryBudget)
                            .build());
        }
        return schedulerConfig;
    }

}
