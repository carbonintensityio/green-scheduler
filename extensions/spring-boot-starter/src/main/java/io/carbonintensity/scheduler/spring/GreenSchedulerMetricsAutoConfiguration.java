package io.carbonintensity.scheduler.spring;

import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.spring.observability.GreenSchedulerMetricsBinder;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Registers the {@code green.scheduler.job.*} Micrometer metrics (see {@link GreenSchedulerMetricsBinder}) when, and
 * only when, both Micrometer and a {@link MeterRegistry} bean are present - i.e. this is a complete no-op, and
 * nothing breaks, for a consumer who never added Micrometer at all.
 * <p>
 * {@link GreenSchedulerAutoConfiguration} never references {@link GreenSchedulerMetricsBinder} or any Micrometer
 * type directly - it only depends on the Micrometer-agnostic
 * {@link io.carbonintensity.scheduler.spring.observability.SchedulingMetricsInstrumenter} interface, resolved to
 * {@code null} via {@code @Autowired(required = false)} when this configuration does not apply.
 */
@Configuration
@ConditionalOnProperty(matchIfMissing = true, prefix = "green-scheduler", name = "enabled", havingValue = "true")
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(MeterRegistry.class)
// Both the Spring Boot 3.x (spring-boot-actuator-autoconfigure) and 4.x (spring-boot-micrometer-metrics,
// after Boot 4 split metrics autoconfiguration out of actuator) package names are listed - a name that
// doesn't resolve on the classpath is silently ignored, so listing both keeps this correct across the
// required/canary Spring Boot lines this project targets (see compatibility/policy.yaml) without a
// version-conditional branch.
@AutoConfigureAfter(name = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration" })
public class GreenSchedulerMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GreenSchedulerMetricsBinder greenSchedulerMetricsBinder(MeterRegistry registry, SchedulerConfig schedulerConfig) {
        return new GreenSchedulerMetricsBinder(registry, schedulerConfig.getClock());
    }
}
