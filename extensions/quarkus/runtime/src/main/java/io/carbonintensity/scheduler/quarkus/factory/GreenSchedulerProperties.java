package io.carbonintensity.scheduler.quarkus.factory;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Green Scheduler properties can be found here. All properties have default values and can be overridden.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "green-scheduler")
public interface GreenSchedulerProperties {

    /**
     * Whether to enable or disable. Default true.
     */
    Optional<Boolean> enabled();

    /**
     * Scheduler start mode. Default Normal.
     */
    Optional<SchedulerConfig.StartMode> startMode();

    /**
     * Number of job executors. Default 10.
     */
    OptionalInt jobExecutors();

    /**
     * Overdue grace period. Default 30 seconds.
     */
    Optional<Duration> overdueGracePeriod();

    /**
     * Shutdown grace period. Default 30 seconds.
     */
    Optional<Duration> shutdownGracePeriod();

    /**
     * CarbonIntensity API key
     */
    Optional<String> apiKey();

    /**
     * CarbonIntensity API url.
     */
    Optional<String> apiUrl();
}
