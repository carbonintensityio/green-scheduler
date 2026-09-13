package io.carbonintensity.scheduler.quarkus.common.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

public final class ImmutableScheduledMethod implements ScheduledMethod {

    private final String invokerClassName;
    private final String declaringClassName;
    private final String methodName;
    private final List<GreenScheduled> schedules;
    private final GreenObserved greenObserved;

    public ImmutableScheduledMethod(String invokerClassName, String declaringClassName, String methodName,
            List<GreenScheduled> schedules) {
        this(invokerClassName, declaringClassName, methodName, schedules, null);
    }

    /**
     * @param greenObserved the {@link GreenObserved} annotation present on this method, or {@code null} if absent
     */
    public ImmutableScheduledMethod(String invokerClassName, String declaringClassName, String methodName,
            List<GreenScheduled> schedules, GreenObserved greenObserved) {
        this.invokerClassName = Objects.requireNonNull(invokerClassName);
        this.declaringClassName = Objects.requireNonNull(declaringClassName);
        this.methodName = Objects.requireNonNull(methodName);
        this.schedules = List.copyOf(schedules);
        this.greenObserved = greenObserved;
    }

    public String getInvokerClassName() {
        return invokerClassName;
    }

    public String getDeclaringClassName() {
        return declaringClassName;
    }

    public String getMethodName() {
        return methodName;
    }

    public List<GreenScheduled> getSchedules() {
        return schedules;
    }

    @Override
    public Optional<GreenObserved> getGreenObserved() {
        return Optional.ofNullable(greenObserved);
    }

}
