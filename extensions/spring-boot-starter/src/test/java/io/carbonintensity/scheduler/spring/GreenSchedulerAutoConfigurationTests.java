package io.carbonintensity.scheduler.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.spring.factory.SchedulerConfigBuilder;

class GreenSchedulerAutoConfigurationTests {

    ApplicationContextRunner contextRunner;

    @BeforeEach
    void setup() {
        contextRunner = new ApplicationContextRunner();
    }

    @Test
    void givenDefaultConfiguration_thenCreateScheduler() {
        this.contextRunner
                .withConfiguration(AutoConfigurations.of(GreenSchedulerAutoConfiguration.class))
                .run(context -> assertThat(context)
                        .hasSingleBean(Scheduler.class)
                        .getBean("green-scheduler", Scheduler.class).isNotNull());
    }

    @Test
    void givenUserConfiguration_whenNoJobsDefined_thenCreateScheduler() {
        this.contextRunner
                .withUserConfiguration(EmptyConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(Scheduler.class));
    }

    @Test
    void givenProperties_whenEnabled_thenCreateScheduler() {
        this.contextRunner
                .withUserConfiguration(EmptyConfiguration.class)
                .withPropertyValues("green-scheduler.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(Scheduler.class));
    }

    @Test
    void givenProperties_whenDisabled_thenDontCreateScheduler() {
        this.contextRunner
                .withUserConfiguration(EmptyConfiguration.class)
                .withPropertyValues("green-scheduler.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(Scheduler.class));
    }

    @Test
    void givenSchedulerConfig_whenEnabled_thenCreateScheduler() {
        this.contextRunner
                .withUserConfiguration(JavaBasedConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(Scheduler.class)
                        .getBean("schedulerConfig")
                        .isSameAs(context.getBean(SchedulerConfig.class)));
    }

    @Test
    void givenSchedulerConfig_whenDisabled_thenDontCreateScheduler() {
        this.contextRunner
                .withUserConfiguration(DisabledConfiguration.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(Scheduler.class)
                        .hasSingleBean(SchedulerConfig.class));
    }

    @Test
    void givenUserConfiguration_whenJobsDefined_thenCreateAndStartScheduler() {
        this.contextRunner
                .withUserConfiguration(ConfigurationWithOneJob.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(Scheduler.class);
                    assertThat(context.getBean(Scheduler.class).isRunning()).isTrue();
                    assertThat(context.getBean(Scheduler.class).getScheduledJobs()).hasSize(1);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class EmptyConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class JavaBasedConfiguration {

        @Bean
        SchedulerConfig schedulerConfig() {
            return new SchedulerConfigBuilder()
                    .enabled(true)
                    .jobExecutorCount(2)
                    .overdueGracePeriod(Duration.parse("PT15M"))
                    .shutdownGracePeriod(Duration.parse("PT15M"))
                    .build();
        }

    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class DisabledConfiguration {

        @Bean
        public SchedulerConfig schedulerConfig() {
            return new SchedulerConfigBuilder()
                    .enabled(false)
                    .build();
        }

    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    public static class ConfigurationWithOneJob {

        @Bean
        public TestScheduledJob testJob() {
            return new TestScheduledJob();
        }

    }

}
