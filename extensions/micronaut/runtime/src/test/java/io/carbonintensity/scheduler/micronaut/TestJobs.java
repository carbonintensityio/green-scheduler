package io.carbonintensity.scheduler.micronaut;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Singleton;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.ScheduledExecution;
import io.carbonintensity.scheduler.observability.GreenObserved;

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

    // Two repeated @GreenScheduled annotations on one method, exercising the GreenSchedules
    // container path (GreenSchedulesAnnotationMapper) rather than the single-annotation one.
    @GreenScheduled(identity = "repeatable-job-1", fixedWindow = "08:00 17:00", duration = "1h", carbonIntensityZone = "NL", timeZone = "Europe/Amsterdam")
    @GreenScheduled(identity = "repeatable-job-2", fixedWindow = "08:00 17:00", duration = "1h", carbonIntensityZone = "NL", timeZone = "Europe/Amsterdam")
    public void repeatableJob() {
        // does nothing, scheduling is asserted via the trigger registry
    }

    // Exercises the @GreenObserved co-presence detection and wiring end to end. No MeterRegistry bean is on this
    // test application's classpath, so this also proves that scheduling a @GreenObserved job never breaks startup
    // when Micrometer/GreenSchedulerMetricsBinder is absent.
    @GreenScheduled(identity = "observed-fixed-window-job", fixedWindow = "08:00 17:00", duration = "1h", carbonIntensityZone = "NL", timeZone = "Europe/Amsterdam")
    @GreenObserved(carbonImpact = true)
    public void observedFixedWindowJob() {
        // does nothing, scheduling is asserted via the trigger registry
    }
}
