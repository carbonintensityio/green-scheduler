package io.carbonintensity.scheduler.micronaut;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.Trigger;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.runtime.ImmutableScheduledMethod;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.processor.ExecutableMethodProcessor;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;

/**
 * Schedules all {@link GreenScheduled} business methods with the green scheduler on startup.
 * <p>
 * The methods were marked with {@link GreenScheduledExecutable} at build time by the
 * {@code green-scheduler-micronaut-processor} module, so Micronaut invokes this processor for each
 * of them when the application context starts. Both the annotation metadata and the method
 * invocation are produced at compile time, no runtime reflection is involved.
 * <p>
 * When the method also carries {@link GreenObserved} - and a {@link GreenSchedulerMetricsBinder} bean is present,
 * i.e. Micrometer is on the classpath and configured - each resulting job is additionally wired up for the
 * {@code green.scheduler.job.*} metrics.
 */
@Singleton
@Requires(property = GreenSchedulerConfigurationProperties.PREFIX + ".enabled", notEquals = "false")
public class GreenScheduledMethodProcessor implements ExecutableMethodProcessor<GreenScheduledExecutable> {

    private static final Logger logger = LoggerFactory.getLogger(GreenScheduledMethodProcessor.class);

    private final BeanContext beanContext;
    private final SimpleScheduler scheduler;
    private final GreenSchedulerMetricsBinder metricsBinder;
    private final Set<String> processedMethods = ConcurrentHashMap.newKeySet();

    public GreenScheduledMethodProcessor(BeanContext beanContext, SimpleScheduler scheduler,
            @Nullable GreenSchedulerMetricsBinder metricsBinder) {
        this.beanContext = beanContext;
        this.scheduler = scheduler;
        this.metricsBinder = metricsBinder;
    }

    @Override
    public void process(BeanDefinition<?> beanDefinition, ExecutableMethod<?, ?> method) {
        List<AnnotationValue<GreenScheduled>> annotationValues = method.getAnnotationValuesByType(GreenScheduled.class);
        if (annotationValues.isEmpty()) {
            return;
        }
        String declaringClassName = beanDefinition.getBeanType().getName();
        String methodDescription = declaringClassName + "#" + method.getMethodName();
        if (!processedMethods.add(methodDescription)) {
            // guards against being called more than once for the same method
            return;
        }
        List<GreenScheduled> schedules = annotationValues.stream()
                .map(AnnotationValueGreenScheduled::new)
                .collect(Collectors.toList());
        GreenObserved greenObserved = method.findAnnotation(GreenObserved.class)
                .map(AnnotationValueGreenObserved::new)
                .orElse(null);
        boolean metricsEnabled = greenObserved != null && metricsBinder != null;

        ScheduledInvoker invoker = new ExecutableMethodInvoker(beanContext, beanDefinition, method);
        if (metricsEnabled) {
            invoker = metricsBinder.trackInvocations(invoker);
        }

        scheduler.scheduleMethod(new ImmutableScheduledMethod(invoker, declaringClassName, method.getMethodName(),
                schedules, greenObserved));
        logger.debug("Scheduled business method {}", methodDescription);

        if (metricsEnabled) {
            registerMetrics(methodDescription, schedules, greenObserved);
        }
    }

    /**
     * Correlates the {@link Trigger}s just created by {@link SimpleScheduler#scheduleMethod} back to the
     * {@link GreenScheduled} entry that produced each of them, since {@code scheduleMethod} is void. The identity
     * of each trigger is re-derived with the exact same rule {@code SimpleScheduler.scheduleMethod} uses
     * (explicit {@link GreenScheduled#identity()}, or {@code <1-based-position>_<methodDescription>} otherwise),
     * which is safe here because {@code schedules} is passed through to {@code scheduleMethod} unchanged and in
     * the same order.
     */
    private void registerMetrics(String methodDescription, List<GreenScheduled> schedules, GreenObserved greenObserved) {
        int nameSequence = 0;
        for (GreenScheduled schedule : schedules) {
            nameSequence++;
            String id = schedule.identity().isEmpty() ? nameSequence + "_" + methodDescription : schedule.identity();
            Trigger trigger = scheduler.getScheduledJob(id);
            if (trigger == null) {
                logger.warn("Could not locate the trigger for job '{}' to register its observability metrics", id);
                continue;
            }
            metricsBinder.registerJob(trigger, schedule, greenObserved);
        }
    }
}
