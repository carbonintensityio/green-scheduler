package io.carbonintensity.scheduler.quarkus.factory;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Mapping for the Quarkus scheduler configuration namespace so that {@code quarkus.scheduler.enabled}
 * remains a recognized key even when the Quarkus scheduler extension is not present on the classpath.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
@ConfigMapping(prefix = "quarkus.scheduler")
public interface QuarkusSchedulerCompatibilityProperties {

    /**
     * Mirror of {@code quarkus.scheduler.enabled}. Defaults to true when unspecified.
     */
    Optional<Boolean> enabled();
}
