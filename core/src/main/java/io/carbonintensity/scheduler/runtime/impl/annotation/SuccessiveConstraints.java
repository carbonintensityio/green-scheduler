package io.carbonintensity.scheduler.runtime.impl.annotation;

import java.time.Duration;

/**
 * Represents the constraints of a successive scheduler.
 */
public class SuccessiveConstraints {

    private final Duration initialMaximumDelay;
    private final Duration minimumGap;
    private final Duration maximumGap;

    public SuccessiveConstraints(Duration initialMaximumDelay, Duration minimumGap, Duration maximumGap) {
        this.initialMaximumDelay = initialMaximumDelay;
        this.minimumGap = minimumGap;
        this.maximumGap = maximumGap;
    }

    public Duration getInitialMaximumDelay() {
        return initialMaximumDelay;
    }

    public Duration getMinimumGap() {
        return minimumGap;
    }

    public Duration getMaximumGap() {
        return maximumGap;
    }
}
