package io.carbonintensity.scheduler.runtime.impl.annotation;

import java.time.ZonedDateTime;

/**
 * Represents the constraints of a fixedTimeWindow scheduler.
 */
public class FixedWindowConstraints {
    private final ZonedDateTime startTime;
    private final ZonedDateTime endTime;

    public FixedWindowConstraints(ZonedDateTime startTime, ZonedDateTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }
}
