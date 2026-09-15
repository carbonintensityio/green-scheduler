package io.carbonintensity.scheduler.spring;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * A job with both {@link GreenScheduled} and {@link GreenObserved} - the valid co-presence case.
 */
public class TestObservedScheduledJob {

    @GreenScheduled(identity = "observedJob", duration = "PT5S", successive = "0H PT1H PT2H", carbonIntensityZone = "nl")
    @GreenObserved(carbonImpact = true)
    public void run() {
        // no-op
    }
}
