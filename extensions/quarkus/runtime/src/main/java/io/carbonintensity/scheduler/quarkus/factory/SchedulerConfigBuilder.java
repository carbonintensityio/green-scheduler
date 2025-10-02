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
    public static final SchedulerConfig.StartMode DEFAULT_START_MODE = SchedulerConfig.StartMode.NORMAL;
    public static final String DEFAULT_API_URL = SchedulerDefaults.DEFAULT_API_URL;
    public static final Boolean DEFAULT_ENABLED = true;

    private boolean enabled;
    private SchedulerConfig.StartMode startMode;
    private Integer jobExecutorCount;
    private Duration shutdownGracePeriod;
    private Duration overdueGracePeriod;
    private String apiKey;
    private String apiUrl;
    private CarbonIntensityApi carbonIntensityApi;

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
        overdueGracePeriod(properties.overdueGracePeriod().orElse(DEFAULT_OVERDUE_GRACE_PERIOD));
        shutdownGracePeriod(properties.shutdownGracePeriod().orElse(DEFAULT_SHUTDOWN_GRACE_PERIOD));
        apiUrl(properties.apiUrl().orElse(DEFAULT_API_URL));
        properties.apiKey().ifPresent(this::apiKey);
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

    public SchedulerConfig build() {
        var schedulerConfig = new SchedulerConfig();
        schedulerConfig.setEnabled(enabled);
        schedulerConfig.setStartMode(startMode);
        schedulerConfig.setOverdueGracePeriod(overdueGracePeriod);
        schedulerConfig.setShutdownGracePeriod(shutdownGracePeriod);
        schedulerConfig.setJobExecutors(jobExecutorCount);

        if (this.carbonIntensityApi != null) {
            schedulerConfig.setCarbonIntensityApi(carbonIntensityApi);
        } else {
            schedulerConfig.setCarbonIntensityApiConfig(
                    new CarbonIntensityApiConfig.Builder()
                            .apiKey(apiKey)
                            .apiUrl(apiUrl)
                            .build());
        }
        return schedulerConfig;
    }

}
