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
     * Maximum number of jobs allowed to start at the exact same carbon-intensity slot within the same
     * zone. Default 0 (disabled): jobs schedule independently and may land on the same moment. When set
     * to a positive value, jobs beyond this limit for a given zone/slot are spread to the next-best slot
     * instead, but a job's configured window always takes priority over this limit.
     */
    OptionalInt maxConcurrentPerSlot();

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
