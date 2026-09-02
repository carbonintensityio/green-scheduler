package io.carbonintensity.compat.micronaut;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.micronaut.context.ApplicationContext;

/**
 * Starts a real Micronaut application context against whatever micronaut.version was requested.
 * This is the part a source-compatible rebuild can't cover: it exercises the compile-time
 * annotation processing and the runtime {@code ExecutableMethodProcessor} as an actual build-time
 * dependency, the same way {@code CompatibilityJobTest} does for Quarkus and Spring Boot.
 */
class CompatibilityJobTest {

    @Test
    void jobIsRegistered() {
        try (ApplicationContext context = ApplicationContext.run()) {
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertTrue(scheduler.getScheduledJobs().stream()
                    .map(Trigger::getId)
                    .anyMatch("compat-check"::equals));
        }
    }
}
