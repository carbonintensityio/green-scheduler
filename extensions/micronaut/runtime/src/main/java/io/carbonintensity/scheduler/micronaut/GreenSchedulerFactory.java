package io.carbonintensity.scheduler.micronaut;

import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiConfig;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;

/**
 * Creates the {@link SimpleScheduler} of the green scheduling library as a Micronaut bean.
 * <p>
 * The default {@link SchedulerConfig} can be replaced by providing a custom {@link SchedulerConfig}
 * bean. A custom {@link CarbonIntensityApi} bean is picked up automatically.
 */
@Factory
public class GreenSchedulerFactory {

    private static final Logger logger = LoggerFactory.getLogger(GreenSchedulerFactory.class);

    @Singleton
    @Requires(missingBeans = SchedulerConfig.class)
    SchedulerConfig schedulerConfig(GreenSchedulerConfigurationProperties properties,
            @Nullable CarbonIntensityApi carbonIntensityApi) {
        var config = new SchedulerConfig();
        config.setEnabled(properties.isEnabled());
        config.setStartMode(properties.getStartMode());
        config.setJobExecutors(properties.getJobExecutors());
        config.setOverdueGracePeriod(properties.getOverdueGracePeriod());
        config.setShutdownGracePeriod(properties.getShutdownGracePeriod());
        if (carbonIntensityApi != null) {
            config.setCarbonIntensityApi(carbonIntensityApi);
        } else {
            config.setCarbonIntensityApiConfig(new CarbonIntensityApiConfig.Builder()
                    .apiKey(properties.getApiKey())
                    .apiUrl(properties.getApiUrl())
                    .build());
        }
        return config;
    }

    @Singleton
    @Bean(preDestroy = "close")
    SimpleScheduler greenScheduler(SchedulerConfig schedulerConfig) {
        logger.info("Creating green scheduler");
        return new SimpleScheduler(schedulerConfig);
    }
}
