package io.carbonintensity.scheduler.spring.factory;

import java.lang.reflect.Method;
import java.util.List;

import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.carbonintensity.scheduler.runtime.MutableScheduledMethod;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.carbonintensity.scheduler.runtime.ScheduledMethod;

/**
 * {@link ScheduledMethod factory}
 */
public class ScheduledMethodFactory {

    public MutableScheduledMethod create(Object bean, Method method) {
        var greenScheduledAnnotationList = getGreenScheduledAnnotations(method);
        var greenObserved = getGreenObservedAnnotation(method);
        var beanClass = AopUtils.getTargetClass(bean);
        var invoker = new MethodScheduledInvoker(bean, method);

        return new MutableScheduledMethodBuilder()
                .scheduledMethod(method)
                .beanClass(beanClass)
                .greenScheduledAnnotationList(greenScheduledAnnotationList)
                .greenObserved(greenObserved)
                .invoker(invoker)
                .build();
    }

    static List<GreenScheduled> getGreenScheduledAnnotations(Method method) {
        return List.of(method.getDeclaredAnnotationsByType(GreenScheduled.class));
    }

    /**
     * @return the {@link GreenObserved} annotation present on the method, or {@code null} if absent
     */
    static GreenObserved getGreenObservedAnnotation(Method method) {
        return AnnotationUtils.findAnnotation(method, GreenObserved.class);
    }

    static class MutableScheduledMethodBuilder {
        private Method scheduledMethod = null;
        private ScheduledInvoker invoker = null;
        private List<GreenScheduled> greenScheduledAnnotationList = null;
        private GreenObserved greenObserved = null;
        private Class<?> beanClass = null;

        public MutableScheduledMethodBuilder scheduledMethod(Method method) {
            Assert.notNull(method, "method cannot be null");
            this.scheduledMethod = method;
            return this;
        }

        public MutableScheduledMethodBuilder invoker(ScheduledInvoker invoker) {
            Assert.notNull(invoker, "invoker cannot be null");
            this.invoker = invoker;
            return this;
        }

        public MutableScheduledMethodBuilder beanClass(Class<?> beanClass) {
            Assert.notNull(beanClass, "beanClass cannot be null");
            this.beanClass = beanClass;
            return this;
        }

        public MutableScheduledMethodBuilder greenScheduledAnnotationList(List<GreenScheduled> greenScheduledAnnotationList) {
            Assert.notNull(greenScheduledAnnotationList, "greenScheduledAnnotationList cannot be null");
            this.greenScheduledAnnotationList = greenScheduledAnnotationList;
            return this;
        }

        /**
         * @param greenObserved the {@link GreenObserved} annotation present on the method, or {@code null} if absent
         */
        public MutableScheduledMethodBuilder greenObserved(GreenObserved greenObserved) {
            this.greenObserved = greenObserved;
            return this;
        }

        public MutableScheduledMethod build() {
            Assert.state(this.scheduledMethod != null, "method cannot be null");
            Assert.state(this.invoker != null, "invoker cannot be null");
            Assert.state(this.beanClass != null, "declaringClassName cannot be null");
            Assert.state(this.greenScheduledAnnotationList != null, "greenScheduledAnnotationList cannot be null");

            var newScheduledMethod = new MutableScheduledMethod();
            newScheduledMethod.setMethodName(this.scheduledMethod.getName());
            newScheduledMethod.setDeclaringClassName(this.beanClass.getName());
            newScheduledMethod.setInvoker(this.invoker);
            newScheduledMethod.setSchedules(this.greenScheduledAnnotationList);
            newScheduledMethod.setGreenObserved(this.greenObserved);
            return newScheduledMethod;
        }

    }
}
