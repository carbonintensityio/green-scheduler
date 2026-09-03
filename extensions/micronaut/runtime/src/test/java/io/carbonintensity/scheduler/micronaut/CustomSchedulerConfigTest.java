package io.carbonintensity.scheduler.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.micronaut.context.ApplicationContext;

/**
 * Verifies that a user-supplied {@link SchedulerConfig} bean takes precedence over
 * {@link GreenSchedulerFactory}'s default one, via {@code @Requires(missingBeans =
 * SchedulerConfig.class)}.
 */
class CustomSchedulerConfigTest {

    static final String ENVIRONMENT = "custom-scheduler-config-test";

    @Test
    void customSchedulerConfigBeanOverridesTheDefault() {
        try (ApplicationContext context = ApplicationContext.run(ENVIRONMENT)) {
            SchedulerConfig config = context.getBean(SchedulerConfig.class);

            assertThat(config).isInstanceOf(TestCustomSchedulerConfig.class);
        }
    }

    @Test
    void defaultSchedulerConfigIsUsedWithoutTheEnvironment() {
        try (ApplicationContext context = ApplicationContext.run()) {
            SchedulerConfig config = context.getBean(SchedulerConfig.class);

            assertThat(config).isNotInstanceOf(TestCustomSchedulerConfig.class);
        }
    }
}
