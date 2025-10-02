package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.quarkus.common.runtime.util.SchedulerUtils;
import io.quarkus.test.QuarkusUnitTest;

public class QuarkusSchedulerConfigurationTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(QuarkusSchedulerConfigurationTest.Jobs.class)
                    .addAsResource("application.properties"));

    @Inject
    Scheduler greenScheduler;

    @ConfigProperty(name = "scheduled.identity1")
    String identity1;

    @Test
    public void testGreenScheduledJobsAreRegisteredWithConfiguration() {
        List<Trigger> triggers = greenScheduler.getScheduledJobs();
        assertEquals(1, triggers.size());
        Trigger trigger = triggers.get(0);
        assertEquals("the_schedule1", identity1);
        assertEquals("the_schedule1", ConfigProvider.getConfig().getConfigValue("scheduled.identity1").getValue());
        assertTrue(SchedulerUtils.isConfigValue("{scheduled.identity1}"));
        assertEquals("the_schedule1", SchedulerUtils.lookUpPropertyValue("{scheduled.identity1}"));
        assertEquals("the_schedule1", trigger.getId());

    }

    static class Jobs {
        @GreenScheduled(identity = "{scheduled.identity1}", successive = "1S 4S 5S", duration = "PT30M", carbonIntensityZone = "NL")
        void ping() {
        }
    }

}
