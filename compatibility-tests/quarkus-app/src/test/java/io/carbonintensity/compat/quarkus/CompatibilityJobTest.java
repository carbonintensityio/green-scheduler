package io.carbonintensity.compat.quarkus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Runs the real Quarkus build/augmentation phase against whatever quarkus.platform.version was
 * requested. This is the part a source-compatible rebuild can't cover: it exercises the deployment
 * jar as an actual build-time extension, which is exactly where the #199 NoSuchMethodError showed up.
 */
@QuarkusTest
class CompatibilityJobTest {

    @Inject
    Scheduler scheduler;

    @Test
    void jobIsRegistered() {
        assertTrue(scheduler.getScheduledJobs().stream()
                .map(Trigger::getId)
                .anyMatch("compat-check"::equals));
    }
}
