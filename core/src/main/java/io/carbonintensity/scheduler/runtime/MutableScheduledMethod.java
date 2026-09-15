package io.carbonintensity.scheduler.runtime;

import java.util.List;
import java.util.Optional;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * This class is mutable so that it can be serialized in a recorder method.
 * <p>
 * Unlike its Quarkus counterpart ({@code io.carbonintensity.scheduler.quarkus.common.runtime.MutableScheduledMethod}),
 * this class is never replayed by a bytecode recorder, so there is no getter/setter naming constraint here -
 * {@link #getGreenObserved()}/{@link #setGreenObserved(GreenObserved)} are named plainly and symmetrically.
 *
 * @see ScheduledMethod
 */
public class MutableScheduledMethod implements ScheduledMethod {

    private ScheduledInvoker invoker;
    private String declaringClassName;
    private String methodName;
    private List<GreenScheduled> schedules;
    private GreenObserved greenObserved;

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

    @Override
    public Optional<GreenObserved> getGreenObserved() {
        return Optional.ofNullable(greenObserved);
    }

    public void setGreenObserved(GreenObserved greenObserved) {
        this.greenObserved = greenObserved;
    }

}
