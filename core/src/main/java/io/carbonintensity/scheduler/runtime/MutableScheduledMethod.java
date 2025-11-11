package io.carbonintensity.scheduler.runtime;

import java.util.List;

import io.carbonintensity.scheduler.GreenScheduled;

/**
 * This class is mutable so that it can be serialized in a recorder method.
 *
 * @see ScheduledMethod
 */
public class MutableScheduledMethod implements ScheduledMethod {

    private ScheduledInvoker invoker;
    private String declaringClassName;
    private String methodName;
    private List<GreenScheduled> schedules;

    public ScheduledInvoker getInvoker() {
        return invoker;
    }

    public void setInvoker(ScheduledInvoker invoker) {
        this.invoker = invoker;
    }

    public String getDeclaringClassName() {
        return declaringClassName;
    }

    public void setDeclaringClassName(String declaringClassName) {
        this.declaringClassName = declaringClassName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public List<GreenScheduled> getSchedules() {
        return schedules;
    }

    public void setSchedules(List<GreenScheduled> schedules) {
        this.schedules = schedules;
    }

}
