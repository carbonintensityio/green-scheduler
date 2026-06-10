package io.carbonintensity.scheduler.micronaut;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Singleton;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;

@Singleton
public class TestJobs {

    static final AtomicInteger SUCCESSIVE_INVOCATIONS = new AtomicInteger();

    @GreenScheduled(identity = "fixed-window-job", fixedWindow = "08:00 17:00", duration = "1h", carbonIntensityZone = "NL", timeZone = "Europe/Amsterdam")
    public void fixedWindowJob() {
        // does nothing, scheduling is asserted via the trigger registry
    }

    @GreenScheduled(identity = "successive-job", successive = "0s 1s 1s", duration = "1m", carbonIntensityZone = "NL")
    public void successiveJob(ScheduledExecution execution) {
        if (execution != null) {
            SUCCESSIVE_INVOCATIONS.incrementAndGet();
        }
    }
}
