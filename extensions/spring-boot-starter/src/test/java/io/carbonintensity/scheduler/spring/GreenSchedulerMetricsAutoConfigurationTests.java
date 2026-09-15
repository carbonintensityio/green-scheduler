package io.carbonintensity.scheduler.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.spring.observability.GreenSchedulerMetricsBinder;
import io.carbonintensity.scheduler.spring.observability.SchedulingMetricsInstrumenter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GreenSchedulerMetricsAutoConfigurationTests {

    ApplicationContextRunner contextRunner;

    @BeforeEach
    void setup() {
        contextRunner = new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(GreenSchedulerAutoConfiguration.class,
                                GreenSchedulerMetricsAutoConfiguration.class));
    }

    @Test
    void givenNoMeterRegistry_thenNoMetricsBinderRegistered() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(GreenSchedulerMetricsBinder.class)
                .doesNotHaveBean(SchedulingMetricsInstrumenter.class));
    }

    @Test
    void givenMeterRegistryBean_thenMetricsBinderRegistered() {
        contextRunner.withUserConfiguration(MeterRegistryConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(GreenSchedulerMetricsBinder.class)
                        .hasSingleBean(SchedulingMetricsInstrumenter.class));
    }

    @Test
    void givenMeterRegistryBeanAndObservedJob_thenSchedulerStartsAndMetricsAreBoundWithoutCircularDependency() {
        contextRunner.withUserConfiguration(MeterRegistryConfiguration.class, ObservedJobConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Scheduler.class).hasSingleBean(GreenSchedulerMetricsBinder.class);
                    // TestObservedScheduledJob has carbonImpact = true, so core also registers its own internal
                    // CarbonImpactBatchTrigger alongside the job's own trigger - hence "contains", not "hasSize(1)".
                    assertThat(context.getBean(Scheduler.class).getScheduledJobs())
                            .extracting(Trigger::getId)
                            .contains("observedJob");
                });
    }

    @Test
    void givenGreenSchedulerDisabled_thenNoMetricsBinderEither() {
        contextRunner.withUserConfiguration(MeterRegistryConfiguration.class)
                .withPropertyValues("green-scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(GreenSchedulerMetricsBinder.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObservedJobConfiguration {

        @Bean
        TestObservedScheduledJob observedJob() {
            return new TestObservedScheduledJob();
        }
    }

}
