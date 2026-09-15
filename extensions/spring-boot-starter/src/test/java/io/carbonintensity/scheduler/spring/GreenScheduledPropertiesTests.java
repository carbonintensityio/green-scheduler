package io.carbonintensity.scheduler.spring;

import static io.carbonintensity.scheduler.spring.GreenSchedulerProperties.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;

class GreenScheduledPropertiesTests {

    @Test
    void whenCreatingProperties_thenSetDefaultValues() {
        GreenSchedulerProperties properties = new GreenSchedulerProperties();

        assertThat(properties.getEnabled()).hasValue(true);
        assertThat(properties.getJobExecutors()).hasValue(DEFAULT_NUMBER_OF_JOB_EXECUTORS);
        assertThat(properties.getMaxConcurrentPerSlot()).hasValue(DEFAULT_MAX_CONCURRENT_PER_SLOT);
        assertThat(properties.getOverdueGracePeriod()).hasValue(DEFAULT_OVERDUE_GRACE_PERIOD);
        assertThat(properties.getShutdownGracePeriod()).hasValue(DEFAULT_SHUTDOWN_GRACE_PERIOD);
        assertThat(properties.getApiUrl()).hasValue(DEFAULT_API_URL);
        assertThat(properties.getApiKey()).isNotPresent();
        assertThat(properties.getCarbonIntensityRetryMaxAttempts()).isNotPresent();
        assertThat(properties.getCarbonIntensityRetryInitialBackoff()).isNotPresent();
        assertThat(properties.getCarbonIntensityRetryBackoffMultiplier()).isNotPresent();
        assertThat(properties.getCarbonIntensityRetryBudget()).isNotPresent();
        assertThat(properties.getCarbonIntensityStalenessThreshold()).isNotPresent();
    }

    @Test
    void whenOverridingDefaultValues_thenSetOverriddenValues() {
        GreenSchedulerProperties properties = new GreenSchedulerProperties(true, SchedulerConfig.StartMode.HALTED, 1, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(2), "apiKey", "apiUrl", 5, Duration.ofMillis(500), 2.5,
                Duration.ofSeconds(3), Duration.ofHours(6));

        assertThat(properties.getEnabled()).hasValue(true);
        assertThat(properties.getJobExecutors()).hasValue(1);
        assertThat(properties.getMaxConcurrentPerSlot()).hasValue(2);
        assertThat(properties.getOverdueGracePeriod()).hasValue(Duration.ofSeconds(1));
        assertThat(properties.getShutdownGracePeriod()).hasValue(Duration.ofSeconds(2));
        assertThat(properties.getApiUrl()).hasValue("apiUrl");
        assertThat(properties.getApiKey()).hasValue("apiKey");
        assertThat(properties.getCarbonIntensityRetryMaxAttempts()).hasValue(5);
        assertThat(properties.getCarbonIntensityRetryInitialBackoff()).hasValue(Duration.ofMillis(500));
        assertThat(properties.getCarbonIntensityRetryBackoffMultiplier()).hasValue(2.5);
        assertThat(properties.getCarbonIntensityRetryBudget()).hasValue(Duration.ofSeconds(3));
        assertThat(properties.getCarbonIntensityStalenessThreshold()).hasValue(Duration.ofHours(6));
    }

}
