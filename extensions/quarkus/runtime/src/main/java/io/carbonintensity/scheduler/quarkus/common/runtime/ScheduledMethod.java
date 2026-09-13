package io.carbonintensity.scheduler.quarkus.common.runtime;

import java.util.List;
import java.util.Optional;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * Scheduled method metadata.
 */
public interface ScheduledMethod {

    String getInvokerClassName();

    String getDeclaringClassName();

    String getMethodName();

    List<GreenScheduled> getSchedules();

    default String getMethodDescription() {
        return getDeclaringClassName() + "#" + getMethodName();
    }

    /**
     * @return the {@link GreenObserved} configuration, or empty if the method opted out of observability data
     */
    default Optional<GreenObserved> getGreenObserved() {
        return Optional.empty();
    }

}
