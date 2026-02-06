package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.quarkus.test.QuarkusUnitTest;

public class QuarkusSchedulerDisablePropertyTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar
                    .addClasses(QuarkusSchedulerDisablePropertyTest.Jobs.class)
                    .addAsResource("application-quarkus-disabled.properties", "application.properties"));

    @Inject
    Scheduler scheduler;

    @Inject
    SchedulerConfig schedulerConfig;

    @Test
    public void shouldDisableSchedulerWhenQuarkusSchedulerDisabled() {
        assertFalse(schedulerConfig.isEnabled());
        assertFalse(scheduler.isRunning());
    }

    static class Jobs {
        @GreenScheduled(identity = "quarkus-disabled", successive = "1S 4S 5S", duration = "PT1M", carbonIntensityZone = "NL")
        void ping() {
        }
    }
}
