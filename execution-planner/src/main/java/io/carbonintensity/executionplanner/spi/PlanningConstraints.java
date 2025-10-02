package io.carbonintensity.executionplanner.spi;

import java.time.Duration;

public interface PlanningConstraints {
    String getIdentity();

    Duration getDuration();

    String getCarbonIntensityZone();
}
