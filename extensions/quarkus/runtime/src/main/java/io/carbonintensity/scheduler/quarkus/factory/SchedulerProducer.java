package io.carbonintensity.scheduler.quarkus.factory;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.quarkus.arc.DefaultBean;

public class SchedulerProducer {
    private final Logger logger = LoggerFactory.getLogger(SchedulerProducer.class);

    @Inject
    GreenSchedulerProperties greenSchedulerProperties;

    @Inject
    Instance<CarbonIntensityApi> carbonIntensityApi;

    @Inject
    QuarkusSchedulerCompatibilityProperties quarkusSchedulerProperties;

    @Produces
    @DefaultBean
    public SchedulerConfig schedulerConfig() {
        var builder = new SchedulerConfigBuilder(greenSchedulerProperties);
        if (!isQuarkusSchedulerEnabled()) {
            builder.disabled();
        }
        if (carbonIntensityApi.isResolvable()) {
            builder.carbonIntensityApi(carbonIntensityApi.get());
        }
        return builder.build();
    }

    private boolean isQuarkusSchedulerEnabled() {
        return quarkusSchedulerProperties.enabled().orElse(Boolean.TRUE);
    }

    @Produces
    @Singleton
    SimpleScheduler createScheduler(SchedulerConfig schedulerConfig) {
        logger.info("Creating green scheduler");
        return new SimpleScheduler(schedulerConfig);
    }

}
