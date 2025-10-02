package io.carbonintensity.executionplanner.planner.successive;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * Data class containing the constraints that are used by {@link SuccessivePlanner} to plan the best window
 */
public class DefaultSuccessivePlanningConstraints
        implements SuccessivePlanningConstraints {

    private final String identity;
    private final ZonedDateTime lastExecutionTime;
    private final ZonedDateTime initialStartTime;
    private final Duration initialMaximumDelay;
    private final Duration minimumGap;
    private final Duration maximumGap;
    private final Duration duration;
    private final String carbonIntensityZone;

    private DefaultSuccessivePlanningConstraints(String identity, ZonedDateTime lastExecutionTime,
            ZonedDateTime initialStartTime,
            Duration initialMaximumDelay,
            Duration minimumGap, Duration maximumGap,
            Duration duration, String carbonIntensityZone) {
        this.identity = identity;
        this.lastExecutionTime = lastExecutionTime;
        this.initialStartTime = initialStartTime;
        this.initialMaximumDelay = initialMaximumDelay;
        this.minimumGap = minimumGap;
        this.maximumGap = maximumGap;
        this.duration = duration;
        this.carbonIntensityZone = carbonIntensityZone;
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

    public Duration getDuration() {
        return duration;
    }

    public String getCarbonIntensityZone() {
        return carbonIntensityZone;
    }

    public String getIdentity() {
        return identity;
    }

    public ZonedDateTime getInitialStartTime() {
        return initialStartTime;
    }

    public ZonedDateTime getLastExecutionTime() {
        return lastExecutionTime;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder from(SuccessivePlanningConstraints constraints) {
        return new Builder()
                .withIdentity(constraints.getIdentity())
                .withLastExecutionTime(constraints.getLastExecutionTime())
                .withInitialMaximumDelay(constraints.getInitialMaximumDelay())
                .withMinimumGap(constraints.getMinimumGap())
                .withMaximumGap(constraints.getMaximumGap())
                .withDuration(constraints.getDuration())
                .withCarbonIntensityZone(constraints.getCarbonIntensityZone());

    }

    public static final class Builder {
        private String identity;
        private ZonedDateTime lastExecutionTime;
        private ZonedDateTime initialStartTime;
        private Duration initialMaximumDelay;
        private Duration minimumGap;
        private Duration maximumGap;
        private Duration duration;
        private String carbonIntensityZone;

        private Builder() {
        }

        public Builder withInitialMaximumDelay(Duration duration) {
            this.initialMaximumDelay = duration;
            return this;
        }

        public Builder withInitialStartTime(ZonedDateTime initialStartTime) {
            this.initialStartTime = initialStartTime;
            return this;
        }

        public Builder withMinimumGap(Duration duration) {
            this.minimumGap = duration;
            return this;
        }

        public Builder withMaximumGap(Duration duration) {
            this.maximumGap = duration;
            return this;
        }

        public Builder withDuration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder withCarbonIntensityZone(String carbonIntensityZone) {
            this.carbonIntensityZone = carbonIntensityZone;
            return this;
        }

        public Builder withIdentity(String identity) {
            this.identity = identity;
            return this;
        }

        public Builder withLastExecutionTime(ZonedDateTime lastExecutionTime) {
            this.lastExecutionTime = lastExecutionTime;
            return this;
        }

        public DefaultSuccessivePlanningConstraints build() {
            return new DefaultSuccessivePlanningConstraints(identity, lastExecutionTime, initialStartTime, initialMaximumDelay,
                    minimumGap,
                    maximumGap,
                    duration, carbonIntensityZone);
        }
    }
}
