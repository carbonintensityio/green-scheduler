package io.carbonintensity.compat.quarkus;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.micrometer.core.instrument.MeterRegistry;
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

    @Inject
    MeterRegistry registry;

    @Test
    void jobIsRegistered() {
        assertTrue(scheduler.getScheduledJobs().stream()
                .map(Trigger::getId)
                .anyMatch("compat-check"::equals));
    }

    /**
     * Exercises the full CIIO-348 augmentation path: @GreenObserved detection (SchedulerProcessor), the
     * capability-gated CDI bean registration for the meter binder, and the meter registration itself
     * (GreenSchedulerMeterBinder). A regression in any of those three would fail this test.
     */
    @Test
    void observedJobIsDetectedAndExportsMicrometerMeters() {
        Trigger trigger = scheduler.getScheduledJob("compat-observed");
        assertNotNull(trigger, "The @GreenObserved job must still be registered");
        assertTrue(trigger.getGreenObserved().isPresent(), "@GreenObserved must be detected on the job");
        assertTrue(trigger.getGreenObserved().get().carbonImpact());

        assertNotNull(registry.find("green.scheduler.job.last_fire_time")
                .tag("identity", "compat-observed").gauge(),
                "The always-on last_fire_time gauge must be registered for the @GreenObserved job");
        assertNotNull(registry.find("green.scheduler.job.carbon_impact_computed_at")
                .tag("identity", "compat-observed").gauge(),
                "The carbon-impact freshness gauge must be registered since carbonImpact=true");
    }
}
