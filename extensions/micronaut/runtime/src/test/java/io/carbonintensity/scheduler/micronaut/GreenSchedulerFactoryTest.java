package io.carbonintensity.scheduler.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;

/**
 * Unit tests for {@link GreenSchedulerFactory}'s {@code schedulerConfig} bean method, called
 * directly rather than through a booted {@link io.micronaut.context.ApplicationContext}.
 * <p>
 * A context-based test would not be able to exercise the "no custom {@link CarbonIntensityApi}"
 * branch in this module, because {@link TestCarbonIntensityApi} is itself a {@code @Singleton}
 * bean on this module's test classpath and would always be injected by Micronaut, regardless of
 * which test started the context.
 */
class GreenSchedulerFactoryTest {

    private final GreenSchedulerFactory factory = new GreenSchedulerFactory();

    @Test
    void buildsCarbonIntensityApiConfigWhenNoCustomApiIsProvided() {
        GreenSchedulerConfigurationProperties properties = new GreenSchedulerConfigurationProperties();
        properties.setApiUrl("https://example.invalid/api");
        properties.setApiKey("test-api-key");

        SchedulerConfig config = factory.schedulerConfig(properties, null);

        assertThat(config.getCarbonIntensityApi()).isNull();
        assertThat(config.getCarbonIntensityApiConfig().getApiUrl()).isEqualTo("https://example.invalid/api");
        assertThat(config.getCarbonIntensityApiConfig().getApiKey()).isEqualTo("test-api-key");
    }

    @Test
    void usesCustomCarbonIntensityApiWhenProvidedInsteadOfBuildingConfig() {
        GreenSchedulerConfigurationProperties properties = new GreenSchedulerConfigurationProperties();
        properties.setApiUrl("https://example.invalid/api");
        properties.setApiKey("test-api-key");
        CarbonIntensityApi customApi = new TestCarbonIntensityApi();

        SchedulerConfig config = factory.schedulerConfig(properties, customApi);

        assertThat(config.getCarbonIntensityApi()).isSameAs(customApi);
        assertThat(config.getCarbonIntensityApiConfig()).isNull();
    }

    @Test
    void forwardsBasicPropertiesOntoSchedulerConfig() {
        GreenSchedulerConfigurationProperties properties = new GreenSchedulerConfigurationProperties();
        properties.setEnabled(false);
        properties.setStartMode(SchedulerConfig.StartMode.HALTED);
        properties.setJobExecutors(4);
        properties.setOverdueGracePeriod(Duration.ofSeconds(5));
        properties.setShutdownGracePeriod(Duration.ofSeconds(10));

        SchedulerConfig config = factory.schedulerConfig(properties, null);

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getStartMode()).isEqualTo(SchedulerConfig.StartMode.HALTED);
        assertThat(config.getJobExecutors()).isEqualTo(4);
        assertThat(config.getOverdueGracePeriod()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getShutdownGracePeriod()).isEqualTo(Duration.ofSeconds(10));
    }
}
