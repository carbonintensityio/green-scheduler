package io.carbonintensity.scheduler.quarkus.common.runtime;

import java.util.List;
import java.util.Optional;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

// This class is mutable so that it can be serialized in a recorder method
public class MutableScheduledMethod implements ScheduledMethod {

    private String invokerClassName;
    private String declaringClassName;
    private String methodName;
    private List<GreenScheduled> schedules;
    private GreenObserved greenObservedAnnotation;

    public String getInvokerClassName() {
        return invokerClassName;
    }

    public void setInvokerClassName(String invokerClassName) {
        this.invokerClassName = invokerClassName;
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
        return Optional.ofNullable(greenObservedAnnotation);
    }

    // Named differently from getGreenObserved()/setGreenObserved(GreenObserved) on purpose: Quarkus's bytecode
    // recorder replays this object at runtime by pairing getters with same-named, same-typed setters (as it already
    // does for e.g. getSchedules()/setSchedules(List)). getGreenObserved() returns Optional<GreenObserved> to satisfy
    // ScheduledMethod, which the recorder can't pair with a GreenObserved-typed setter - so recording happens through
    // this plain, symmetric accessor instead.
    public GreenObserved getGreenObservedAnnotation() {
        return greenObservedAnnotation;
    }

    public void setGreenObservedAnnotation(GreenObserved greenObservedAnnotation) {
        this.greenObservedAnnotation = greenObservedAnnotation;
    }

}
