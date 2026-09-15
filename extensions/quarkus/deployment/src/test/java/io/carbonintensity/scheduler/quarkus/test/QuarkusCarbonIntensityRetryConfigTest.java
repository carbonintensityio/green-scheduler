package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Proves the CIIO-470 carbon-intensity retry/staleness properties configured via
 * {@code green-scheduler.carbon-intensity-*} actually arrive in {@link SchedulerConfig}'s
 * {@code CarbonIntensityApiConfig} - not just that {@code core}'s own builder/config classes accept these
 * values in isolation. See CONTRIBUTING.md's "Coding Guidelines" on extension-level tests for
 * core-consumed, extension-supplied data.
 */
public class QuarkusCarbonIntensityRetryConfigTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addAsResource("application-carbon-intensity-retry.properties", "application.properties"));

    @Inject
    SchedulerConfig schedulerConfig;

    @Test
    public void testCarbonIntensityRetryPropertiesReachCarbonIntensityApiConfig() {
        var config = schedulerConfig.getCarbonIntensityApiConfig();

        assertEquals(5, config.getRetryMaxAttempts());
        assertEquals(Duration.ofMillis(500), config.getRetryInitialBackoff());
        assertEquals(2.5, config.getRetryBackoffMultiplier());
        assertEquals(Duration.ofSeconds(3), config.getRetryBudget());
        assertEquals(Duration.ofHours(6), config.getStalenessThreshold());
    }
}
