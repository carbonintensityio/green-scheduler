package io.carbonintensity.scheduler.spring;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * A job with {@link GreenObserved} but no {@link GreenScheduled} - the invalid co-presence case that
 * {@link GreenSchedulerBeanProcessor} must reject.
 */
public class TestObservedOnlyJob {

    @GreenObserved
    public void run() {
        // no-op
    }
}
