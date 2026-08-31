package io.carbonintensity.compat.springboot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.spring.GreenSchedulerProperties;

/**
 * Boots the real autoconfiguration against whatever spring.boot.version was requested and checks
 * both halves of what a consumer relies on: the job actually getting scheduled, and the
 * {@code green-scheduler.*} properties binding correctly through {@code @ConstructorBinding} /
 * {@code @Validated}.
 */
@SpringBootTest(classes = { CompatibilityJobTest.TestApplication.class })
class CompatibilityJobTest {

    @Autowired
    Scheduler scheduler;

    @Autowired
    GreenSchedulerProperties greenSchedulerProperties;

    @Test
    @DirtiesContext
    void jobIsRegistered() {
        assertThat(scheduler.getScheduledJobs())
                .extracting(Trigger::getId)
                .contains("compat-check");
    }

    @Test
    @DirtiesContext
    void propertiesAreBound() {
        assertThat(greenSchedulerProperties.getApiUrl()).contains("http://localhost:1");
    }

    @EnableAutoConfiguration
    public static class TestApplication {

        @Bean
        CompatibilityJob compatibilityJob() {
            return new CompatibilityJob();
        }
    }
}
