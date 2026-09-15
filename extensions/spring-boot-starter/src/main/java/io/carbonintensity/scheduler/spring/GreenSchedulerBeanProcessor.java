package io.carbonintensity.scheduler.spring;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.lang.NonNull;
import org.springframework.util.ObjectUtils;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * Finder class for checking all spring managed beans and keeps {@link GreenScheduled} annotated methods.
 * <p>
 * Also enforces, at bean post-processing time, that {@link GreenObserved} never appears without a co-located
 * {@link GreenScheduled} - the same rule the Quarkus extension enforces at build time via
 * {@code GreenScheduledAnnotationValidation}, but checked here at runtime since Spring has no build-time hook.
 */
public final class GreenSchedulerBeanProcessor implements BeanPostProcessor, Iterator<GreenSchedulerBeanInfo> {

    private final Logger logger = LoggerFactory.getLogger(GreenSchedulerBeanProcessor.class);
    private final Map<String, GreenSchedulerBeanInfo> scheduledBeanInfoMap = Collections.synchronizedMap(new HashMap<>());

    /**
     * Checks if the given method has {@link GreenScheduled} annotation
     *
     * @param method spring method
     * @return true if the method has at least one annotation
     */
    static boolean isScheduledMethod(Method method) {
        var annotations = AnnotationUtils.findAnnotation(method, GreenScheduled.class);
        return !ObjectUtils.isEmpty(annotations);
    }

    /**
     * Checks if the given method has a {@link GreenObserved} annotation.
     *
     * @param method spring method
     * @return true if the method is annotated with {@link GreenObserved}
     */
    static boolean isObservedMethod(Method method) {
        return AnnotationUtils.findAnnotation(method, GreenObserved.class) != null;
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String beanName) {
        var beanClass = AopUtils.getTargetClass(bean);
        var methods = beanClass.getDeclaredMethods();
        // Every declared method is checked for @GreenObserved, not just the ones already carrying @GreenScheduled -
        // otherwise a @GreenObserved-only method (a co-presence mistake) would simply be skipped rather than
        // rejected.
        Stream.of(methods).forEach(method -> {
            boolean scheduled = isScheduledMethod(method);
            if (isObservedMethod(method) && !scheduled) {
                throw new IllegalStateException(
                        "@GreenObserved requires @GreenScheduled to be present on the same method: "
                                + beanClass.getName() + "#" + method.getName() + "()");
            }
            if (scheduled) {
                registerBean(beanName, bean, method);
            }
        });
        return bean;
    }

    private void registerBean(String beanName, Object bean, Method method) {
        logger.info("Registering scheduled bean {} ", beanName);
        var uniqueKey = String.format("%s#%s", beanName, method.getName());
        scheduledBeanInfoMap.put(uniqueKey, new GreenSchedulerBeanInfo(bean, method));
    }

    public List<GreenSchedulerBeanInfo> getScheduledBeanInfoList() {
        return new ArrayList<>(scheduledBeanInfoMap.values());
    }

    @Override
    public boolean hasNext() {
        return !scheduledBeanInfoMap.isEmpty();
    }

    @Override
    public GreenSchedulerBeanInfo next() {
        var key = scheduledBeanInfoMap.keySet().iterator().next();
        return scheduledBeanInfoMap.remove(key);
    }
}
