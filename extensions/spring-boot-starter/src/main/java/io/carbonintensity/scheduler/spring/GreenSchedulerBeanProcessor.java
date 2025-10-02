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

/**
 * Finder class for checking all spring managed beans and keeps {@link GreenScheduled} annotated methods.
 */
public final class GreenSchedulerBeanProcessor implements BeanPostProcessor, Iterator<GreenSchedulerBeanInfo> {

    private final Logger logger = LoggerFactory.getLogger(GreenSchedulerBeanProcessor.class);
    private final Map<String, GreenSchedulerBeanInfo> scheduledBeanInfoMap = Collections.synchronizedMap(new HashMap<>());

    /**
     * Checks if given method has {@link GreenScheduled} annotation
     *
     * @param method spring method
     * @return true if method has at least one annotation
     */
    static boolean isScheduledMethod(Method method) {
        var annotations = AnnotationUtils.findAnnotation(method, GreenScheduled.class);
        return !ObjectUtils.isEmpty(annotations);
    }

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) {
        var beanClass = AopUtils.getTargetClass(bean);
        var methods = beanClass.getDeclaredMethods();
        Stream.of(methods)
                .filter(GreenSchedulerBeanProcessor::isScheduledMethod)
                .forEach(method -> registerBean(beanName, bean, method));
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
