package io.carbonintensity.scheduler.micronaut;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.runtime.ImmutableScheduledMethod;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.micronaut.context.BeanContext;
import io.micronaut.context.processor.ExecutableMethodProcessor;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;

/**
 * Schedules all {@link GreenScheduled} business methods with the green scheduler on startup.
 * <p>
 * The methods were marked with {@link GreenScheduledExecutable} at build time by the
 * {@code green-scheduler-micronaut-processor} module, so Micronaut invokes this processor for each
 * of them when the application context starts. Both the annotation metadata and the method
 * invocation are produced at compile time, no runtime reflection is involved.
 */
@Singleton
public class GreenScheduledMethodProcessor implements ExecutableMethodProcessor<GreenScheduledExecutable> {

    private static final Logger logger = LoggerFactory.getLogger(GreenScheduledMethodProcessor.class);

    private final BeanContext beanContext;
    private final SimpleScheduler scheduler;
    private final SchedulerConfig schedulerConfig;
    private final Set<String> processedMethods = ConcurrentHashMap.newKeySet();

    public GreenScheduledMethodProcessor(BeanContext beanContext, SimpleScheduler scheduler,
            SchedulerConfig schedulerConfig) {
        this.beanContext = beanContext;
        this.scheduler = scheduler;
        this.schedulerConfig = schedulerConfig;
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
        if (!schedulerConfig.isEnabled()) {
            logger.info("Green scheduler is disabled, not scheduling method {}", methodDescription);
            return;
        }
        List<GreenScheduled> schedules = annotationValues.stream()
                .map(AnnotationValueGreenScheduled::new)
                .collect(Collectors.toList());
        ScheduledInvoker invoker = new ExecutableMethodInvoker(beanContext, beanDefinition, method);
        scheduler.scheduleMethod(
                new ImmutableScheduledMethod(invoker, declaringClassName, method.getMethodName(), schedules));
        logger.debug("Scheduled business method {}", methodDescription);
    }
}
