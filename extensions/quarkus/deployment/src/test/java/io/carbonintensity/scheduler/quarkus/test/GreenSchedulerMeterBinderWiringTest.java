package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies the capability-gated registration of {@code GreenSchedulerMeterBinder} (CIIO-348): with
 * {@code quarkus-micrometer} on the application's classpath, the bean must be registered and must actually export
 * meters for a {@code @GreenObserved} job. The build step deliberately checks
 * {@code MetricsCapabilityBuildItem#metricsSupported(MetricsFactory.MICROMETER)} rather than the generic
 * {@code Capability.METRICS} - a regression back to the generic capability would break every consumer using a
 * non-Micrometer metrics extension (e.g. {@code quarkus-smallrye-metrics}) instead of catching it here, so this test
 * exists to pin the happy path this fix must keep working.
 */
public class GreenSchedulerMeterBinderWiringTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar.addClasses(GreenSchedulerMeterBinderWiringTest.Jobs.class));

    @Inject
    MeterRegistry registry;

    @Test
    public void testMeterBinderExportsAlwaysOnGaugesForAnObservedJob() {
        assertNotNull(registry.find("green.scheduler.job.last_fire_time").tag("identity", "meter-binder-check").gauge(),
                "GreenSchedulerMeterBinder must be registered and bind meters when quarkus-micrometer is present");
    }

    static class Jobs {
        @GreenScheduled(identity = "meter-binder-check", successive = "1S 4S 5S", duration = "PT30M", carbonIntensityZone = "NL")
        @GreenObserved
        void ping() {
        }
    }

}
