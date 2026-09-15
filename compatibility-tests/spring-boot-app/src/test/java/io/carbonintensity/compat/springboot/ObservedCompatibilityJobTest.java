package io.carbonintensity.compat.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;

/**
 * Boots the real autoconfiguration with a {@link MeterRegistry} bean present, proving the
 * {@code green.scheduler.job.*} metrics wiring (CIIO-349) builds and boots cleanly against whatever
 * spring.boot.version this matrix run targets, and actually exports metrics for a {@code @GreenObserved} job.
 */
@SpringBootTest(classes = { ObservedCompatibilityJobTest.TestApplication.class })
class ObservedCompatibilityJobTest {

    private static final String IDENTITY = "observed-compat-check";

    @Autowired
    Scheduler scheduler;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    @DirtiesContext
    void jobIsRegistered() {
        assertThat(scheduler.getScheduledJobs())
                .extracting(Trigger::getId)
                .contains(IDENTITY);
    }

    @Test
    @DirtiesContext
    void metricsAreExported() {
        assertThat(meterRegistry.get("green.scheduler.job.last_fire_time")
                .tag("identity", IDENTITY)
                .tag("strategy", "successive")
                .tag("zone", "NL")
                .gauge()).isNotNull();
        assertThat(meterRegistry.get("green.scheduler.job.overdue").tag("identity", IDENTITY).gauge()).isNotNull();
        assertThat(meterRegistry.get("green.scheduler.job.carbon_impact_computed_at").tag("identity", IDENTITY).gauge())
                .isNotNull();
    }

    @EnableAutoConfiguration
    public static class TestApplication {

        @Bean
        ObservedCompatibilityJob observedCompatibilityJob() {
            return new ObservedCompatibilityJob();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
