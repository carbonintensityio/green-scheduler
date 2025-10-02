package io.carbonintensity.scheduler.spring.factory;

import static io.carbonintensity.scheduler.spring.GreenSchedulerProperties.DEFAULT_API_URL;
import static io.carbonintensity.scheduler.spring.GreenSchedulerProperties.DEFAULT_NUMBER_OF_JOB_EXECUTORS;
import static io.carbonintensity.scheduler.spring.GreenSchedulerProperties.DEFAULT_OVERDUE_GRACE_PERIOD;
import static io.carbonintensity.scheduler.spring.GreenSchedulerProperties.DEFAULT_SHUTDOWN_GRACE_PERIOD;
import static io.carbonintensity.scheduler.spring.GreenSchedulerProperties.DEFAULT_START_MODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiConfig;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SchedulerDefaults;

class SchedulerConfigBuilderTests {

    SchedulerConfigBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SchedulerConfigBuilder();
    }

    @Test
    void whenBuilding_thenBuildWithDefaultValues() {
        var schedulerConfig = builder
                .build();

        assertThat(schedulerConfig.isEnabled()).isTrue();
        assertThat(schedulerConfig.getJobExecutors()).isEqualTo(DEFAULT_NUMBER_OF_JOB_EXECUTORS);
        assertThat(schedulerConfig.getStartMode()).isEqualTo(DEFAULT_START_MODE);
        assertThat(schedulerConfig.getOverdueGracePeriod()).isEqualTo(DEFAULT_OVERDUE_GRACE_PERIOD);
        assertThat(schedulerConfig.getShutdownGracePeriod()).isEqualTo(DEFAULT_SHUTDOWN_GRACE_PERIOD);
        assertThat(schedulerConfig.getCarbonIntensityApiConfig()).isNotNull();
        assertThat(schedulerConfig.getCarbonIntensityApiConfig().getApiUrl()).isEqualTo(DEFAULT_API_URL);
        assertThat(schedulerConfig.getCarbonIntensityApi()).isNull();
    }

    @Test
    void testEnabled() {
        var schedulerConfig = builder
                .enabled(false)
                .build();

        assertThat(schedulerConfig.isEnabled()).isFalse();

        schedulerConfig = builder
                .enabled(true)
                .build();

        assertThat(schedulerConfig.isEnabled()).isTrue();

        schedulerConfig = builder
                .enabled()
                .build();

        assertThat(schedulerConfig.isEnabled()).isTrue();
        assertThrows(IllegalArgumentException.class, () -> builder.enabled(null));
    }

    @Test
    void testDisabled() {
        var schedulerConfig = builder
                .disabled()
                .build();

        assertThat(schedulerConfig.isEnabled()).isFalse();
    }

    @Test
    void testJobExecutorCount() {
        var schedulerConfig = builder
                .jobExecutorCount(2)
                .build();

        assertThat(schedulerConfig.getJobExecutors()).isEqualTo(2);
        assertThrows(IllegalArgumentException.class, () -> builder.jobExecutorCount(null));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1 })
    void whenSettingInvalidJobExecutorCount_thenThrowException(Integer jobExecutors) {
        assertThrows(IllegalArgumentException.class, () -> builder.jobExecutorCount(jobExecutors));
    }

    @Test
    void testStartMode() {
        var schedulerConfig = builder
                .startMode(SchedulerConfig.StartMode.FORCED)
                .build();

        assertThat(schedulerConfig.getStartMode()).isEqualTo(SchedulerConfig.StartMode.FORCED);
        assertThrows(IllegalArgumentException.class, () -> builder.startMode(null));
    }

    @Test
    void testShutdownGracePeriod() {
        var duration = Duration.ofHours(1);
        var schedulerConfig = builder
                .shutdownGracePeriod(duration)
                .build();

        assertThat(schedulerConfig.getShutdownGracePeriod()).isEqualTo(duration);
        assertThrows(IllegalArgumentException.class, () -> builder.shutdownGracePeriod(null));
    }

    @ParameterizedTest
    @ValueSource(strings = { "P1D", "PT24H", "PT25H", "PT-1S" })
    void whenSettingInvalidShutdownGracePeriod_thenThrowException(String durationText) {
        var duration = Duration.parse(durationText);
        assertThrows(IllegalArgumentException.class, () -> builder.shutdownGracePeriod(duration));
    }

    @Test
    void testOverdueGracePeriod() {
        var duration = Duration.ofHours(1);
        var schedulerConfig = builder
                .overdueGracePeriod(duration)
                .build();

        assertThat(schedulerConfig.getOverdueGracePeriod()).isEqualTo(duration);
        assertThrows(IllegalArgumentException.class, () -> builder.overdueGracePeriod(null));
    }

    @ParameterizedTest
    @ValueSource(strings = { "P1D", "PT24H", "PT25H", "PT-1S" })
    void whenSettingInvalidOverdueGracePeriod_thenThrowException(String durationText) {
        var duration = Duration.parse(durationText);
        assertThrows(IllegalArgumentException.class, () -> builder.overdueGracePeriod(duration));
    }

    @Test
    void testApiConfig() {
        var schedulerConfig = builder
                .apiKey("apiKey")
                .apiUrl("apiUrl")
                .build();

        assertThat(schedulerConfig.getCarbonIntensityApiConfig()).isNotNull();
        assertThat(schedulerConfig.getCarbonIntensityApiConfig())
                .usingRecursiveComparison().isEqualTo(
                        new CarbonIntensityApiConfig.Builder()
                                .apiKey("apiKey")
                                .apiUrl("apiUrl")
                                .build());

        schedulerConfig = new SchedulerConfigBuilder()
                .apiKey("apiKey")
                .build();
        assertThat(schedulerConfig.getCarbonIntensityApiConfig().getApiUrl()).isEqualTo(SchedulerDefaults.DEFAULT_API_URL);
    }

    @Test
    void testCarbonIntensityApi() {
        var mockApi = mock(CarbonIntensityApi.class);
        var schedulerConfig = builder
                .carbonIntensityApi(mockApi)
                .build();

        assertThat(schedulerConfig.getCarbonIntensityApi()).isEqualTo(mockApi);
        assertThat(schedulerConfig.getCarbonIntensityApiConfig()).isNull();
    }
}
