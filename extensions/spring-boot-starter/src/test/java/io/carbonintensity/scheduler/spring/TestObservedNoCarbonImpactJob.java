package io.carbonintensity.scheduler.spring;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * A job with {@link GreenObserved} (default {@code carbonImpact = false}) on a plain cron
 * {@link GreenScheduled} - exercises the always-on metrics without the carbon-impact ones, and the
 * {@code strategy=cron} tag.
 */
public class TestObservedNoCarbonImpactJob {

    @GreenScheduled(identity = "observedNoImpactJob", cron = "0 0 * * * ?", carbonIntensityZone = "nl")
    @GreenObserved
    public void run() {
        // no-op
    }
}
