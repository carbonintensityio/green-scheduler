package io.carbonintensity.scheduler.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.Trigger;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;

/**
 * Integration test starting a real Micronaut application context, verifying that
 * {@link GreenScheduled} annotations were processed at compile time (reflection-free
 * {@link ExecutableMethod}s with the full annotation metadata) and that the annotated methods are
 * scheduled and invoked by the green scheduler at runtime.
 */
class GreenScheduledMicronautTest {

    @Test
    void greenScheduledMethodsAreProcessedAtBuildTimeAndInvokedAtRuntime() {
        try (ApplicationContext context = ApplicationContext.run()) {
            BeanDefinition<TestJobs> beanDefinition = context.getBeanDefinition(TestJobs.class);

            // The annotation mapper marked the methods @Executable(processOnStartup = true) at
            // compile time, so the generated bean definition requires method processing ...
            assertThat(beanDefinition.requiresMethodProcessing()).isTrue();

            // ... and contains compile time generated, reflection-free executable methods with the
            // full @GreenScheduled annotation metadata.
            ExecutableMethod<TestJobs, ?> fixedWindowJob = beanDefinition
                    .findMethod("fixedWindowJob")
                    .orElseThrow();
            List<AnnotationValue<GreenScheduled>> schedules = fixedWindowJob
                    .getAnnotationValuesByType(GreenScheduled.class);
            assertThat(schedules).hasSize(1);
            assertThat(schedules.get(0).stringValue("fixedWindow")).contains("08:00 17:00");
            assertThat(schedules.get(0).stringValue("duration")).contains("1h");
            assertThat(schedules.get(0).stringValue("carbonIntensityZone")).contains("NL");
            assertThat(schedules.get(0).stringValue("timeZone")).contains("Europe/Amsterdam");
            assertThat(beanDefinition.findMethod("successiveJob", ScheduledExecution.class)).isPresent();

            // All business methods are registered with the green scheduler, including both
            // schedules of the repeatable-annotation job.
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.getScheduledJobs())
                    .extracting(Trigger::getId)
                    .containsExactlyInAnyOrder("fixed-window-job", "successive-job", "repeatable-job-1",
                            "repeatable-job-2");

            // And the successive job is actually invoked.
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(TestJobs.SUCCESSIVE_INVOCATIONS.get()).isPositive());
        }
    }

    @Test
    void schedulerCanBeDisabledByProperty() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("green-scheduler.enabled", false))) {
            Scheduler scheduler = context.getBean(Scheduler.class);
            assertThat(scheduler.getScheduledJobs()).isEmpty();
            assertThat(scheduler.isRunning()).isFalse();
        }
    }
}
