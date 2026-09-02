package io.carbonintensity.scheduler.micronaut;

import jakarta.inject.Singleton;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.micronaut.context.annotation.Requires;

/**
 * A custom {@link SchedulerConfig} bean, only active in the
 * {@value CustomSchedulerConfigTest#ENVIRONMENT} environment, used to verify that
 * {@link GreenSchedulerFactory}'s default {@code schedulerConfig} bean method backs off via its
 * {@code @Requires(missingBeans = SchedulerConfig.class)} when a user supplies their own.
 * <p>
 * Scoped to a dedicated environment (rather than being an unconditional {@code @Singleton}) so it
 * does not also get picked up by every other test in this module.
 */
@Singleton
@Requires(env = CustomSchedulerConfigTest.ENVIRONMENT)
class TestCustomSchedulerConfig extends SchedulerConfig {

    TestCustomSchedulerConfig() {
        // a CarbonIntensityApi(Config) is required for SimpleScheduler to start; the actual value
        // is irrelevant here, only that this custom bean - and not the factory's default one - is used
        setCarbonIntensityApi(new TestCarbonIntensityApi());
    }
}
