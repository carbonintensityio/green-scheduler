package io.carbonintensity.scheduler.spring;

import java.lang.reflect.Method;
import java.util.Objects;

import org.springframework.util.Assert;

/**
 * Green scheduled bean info class.
 */
public class GreenSchedulerBeanInfo {

    private final Object bean;
    private final Method beanMethod;

    /**
     * Constructs GreenScheduledBeanInfo
     *
     * @param bean bean containing scheduled beanMethod
     * @param beanMethod annotated bean method
     */
    public GreenSchedulerBeanInfo(Object bean, Method beanMethod) {
        Assert.notNull(bean, "bean must not be null");
        Assert.notNull(beanMethod, "beanMethod must not be null");
        this.bean = bean;
        this.beanMethod = beanMethod;
    }

    public Object getBean() {
        return bean;
    }

    public Method getBeanMethod() {
        return beanMethod;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof GreenSchedulerBeanInfo))
            return false;
        GreenSchedulerBeanInfo that = (GreenSchedulerBeanInfo) o;
        return Objects.equals(getBean(), that.getBean()) && Objects.equals(getBeanMethod(), that.getBeanMethod());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getBean(), getBeanMethod());
    }
}
