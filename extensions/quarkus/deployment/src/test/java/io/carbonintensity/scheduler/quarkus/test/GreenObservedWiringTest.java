package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.quarkus.common.runtime.ScheduledMethod;
import io.carbonintensity.scheduler.quarkus.common.runtime.SchedulerContext;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies that {@code @GreenObserved} on a {@code @GreenScheduled} method is actually detected and carried through
 * the Quarkus extension's own scheduled-method metadata - the gap found while running the internal pilot (CIIO-449):
 * without this, {@code carbonImpact} silently never activates for any Quarkus adopter, no matter how the annotation
 * is configured.
 */
public class GreenObservedWiringTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar.addClasses(GreenObservedWiringTest.Jobs.class));

    @Inject
    SchedulerContext context;

    @Test
    public void testGreenObservedIsDetectedAndCarbonImpactFlagIsCarriedThrough() {
        List<ScheduledMethod> scheduledMethods = context.getScheduledMethods();
        assertEquals(1, scheduledMethods.size());

        ScheduledMethod method = scheduledMethods.get(0);
        assertTrue(method.getGreenObserved().isPresent(),
                "@GreenObserved must be detected on a @GreenScheduled method");
        assertTrue(method.getGreenObserved().get().carbonImpact());
    }

    static class Jobs {
        @GreenScheduled(successive = "1S 4S 5S", duration = "PT30M", carbonIntensityZone = "NL")
        @GreenObserved(carbonImpact = true)
        void ping() {
        }
    }

}
