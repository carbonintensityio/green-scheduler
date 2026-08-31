package io.carbonintensity.scheduler.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.micronaut.context.ApplicationContext;

/**
 * Verifies that {@code green-scheduler.*} properties are bound onto
 * {@link GreenSchedulerConfigurationProperties} and forwarded onto {@link SchedulerConfig} by
 * {@link GreenSchedulerFactory}.
 * <p>
 * {@code apiUrl}/{@code apiKey} are asserted directly on the properties bean rather than on
 * {@link SchedulerConfig}, because {@link TestCarbonIntensityApi} is always present as a bean in
 * this module's tests, which means {@link GreenSchedulerFactory} always takes the "custom
 * {@code CarbonIntensityApi}" branch and never builds a {@code CarbonIntensityApiConfig} from
 * those two properties (see {@link GreenSchedulerFactoryTest} for that behaviour).
 */
class GreenSchedulerConfigurationPropertiesTest {

    @Test
    void defaultsAreUsedWhenNoPropertiesAreConfigured() {
        try (ApplicationContext context = ApplicationContext.run()) {
            GreenSchedulerConfigurationProperties properties = context
                    .getBean(GreenSchedulerConfigurationProperties.class);

            assertThat(properties.isEnabled()).isTrue();
            assertThat(properties.getStartMode()).isEqualTo(SchedulerConfig.StartMode.NORMAL);
            assertThat(properties.getApiKey()).isNull();

            SchedulerConfig config = context.getBean(SchedulerConfig.class);
            assertThat(config.getJobExecutors()).isEqualTo(properties.getJobExecutors());
            assertThat(config.getOverdueGracePeriod()).isEqualTo(properties.getOverdueGracePeriod());
            assertThat(config.getShutdownGracePeriod()).isEqualTo(properties.getShutdownGracePeriod());
        }
    }

    @Test
    void customPropertiesAreBound() {
        Map<String, Object> customProperties = Map.of(
                "green-scheduler.start-mode", "HALTED",
                "green-scheduler.job-executors", 4,
                "green-scheduler.overdue-grace-period", "PT5S",
                "green-scheduler.shutdown-grace-period", "PT10S",
                "green-scheduler.api-url", "https://example.invalid/api",
                "green-scheduler.api-key", "test-api-key");

        try (ApplicationContext context = ApplicationContext.run(customProperties)) {
            GreenSchedulerConfigurationProperties properties = context
                    .getBean(GreenSchedulerConfigurationProperties.class);

            assertThat(properties.getStartMode()).isEqualTo(SchedulerConfig.StartMode.HALTED);
            assertThat(properties.getJobExecutors()).isEqualTo(4);
            assertThat(properties.getOverdueGracePeriod()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getShutdownGracePeriod()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getApiUrl()).isEqualTo("https://example.invalid/api");
            assertThat(properties.getApiKey()).isEqualTo("test-api-key");

            SchedulerConfig config = context.getBean(SchedulerConfig.class);
            assertThat(config.getStartMode()).isEqualTo(SchedulerConfig.StartMode.HALTED);
            assertThat(config.getJobExecutors()).isEqualTo(4);
            assertThat(config.getOverdueGracePeriod()).isEqualTo(Duration.ofSeconds(5));
            assertThat(config.getShutdownGracePeriod()).isEqualTo(Duration.ofSeconds(10));
        }
    }
}
