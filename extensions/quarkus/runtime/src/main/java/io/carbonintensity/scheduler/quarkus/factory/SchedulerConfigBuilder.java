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
    private Integer retryMaxAttempts;
    private Duration retryInitialBackoff;
    private Double retryBackoffMultiplier;
    private Duration retryBudget;
    private Duration stalenessThreshold;

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

    public SchedulerConfigBuilder retryMaxAttempts(Integer retryMaxAttempts) {
        Assert.notNull(retryMaxAttempts, "retryMaxAttempts cannot be null");
        Assert.isTrue(retryMaxAttempts >= 1, "retryMaxAttempts must be at least 1");
        this.retryMaxAttempts = retryMaxAttempts;
        return this;
    }

    public SchedulerConfigBuilder retryInitialBackoff(Duration retryInitialBackoff) {
        Assert.notNull(retryInitialBackoff, "retryInitialBackoff cannot be null");
        this.retryInitialBackoff = retryInitialBackoff;
        return this;
    }

    public SchedulerConfigBuilder retryBackoffMultiplier(Double retryBackoffMultiplier) {
        Assert.notNull(retryBackoffMultiplier, "retryBackoffMultiplier cannot be null");
        Assert.isTrue(retryBackoffMultiplier >= 1.0, "retryBackoffMultiplier must be at least 1.0");
        this.retryBackoffMultiplier = retryBackoffMultiplier;
        return this;
    }

    public SchedulerConfigBuilder retryBudget(Duration retryBudget) {
        Assert.notNull(retryBudget, "retryBudget cannot be null");
        this.retryBudget = retryBudget;
        return this;
    }

    public SchedulerConfigBuilder stalenessThreshold(Duration stalenessThreshold) {
        Assert.notNull(stalenessThreshold, "stalenessThreshold cannot be null");
        this.stalenessThreshold = stalenessThreshold;
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
                            .build());
        }
        return schedulerConfig;
    }

}
