package io.carbonintensity.compat.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * A minimal scheduled job, just like a real consumer would write. Its only purpose here is to give
 * the Quarkus build a {@link GreenScheduled} method to process, so the extension's augmentation
 * code path actually runs (that's the phase that broke consumers in #199).
 */
@ApplicationScoped
public class CompatibilityJob {

    @GreenScheduled(identity = "compat-check", successive = "0H 1H 2H", duration = "PT5M", carbonIntensityZone = "NL")
    void run() {
        // never invoked: start-mode is HALTED for this check
    }

    /**
     * A separate job, kept apart from {@link #run()} above, so a regression in the {@code @GreenObserved}/Micrometer
     * augmentation path (CIIO-348) is caught independently of the plain {@code @GreenScheduled} regression check.
     */
    @GreenScheduled(identity = "compat-observed", fixedWindow = "08:00 09:00", duration = "PT5M", carbonIntensityZone = "NL")
    @GreenObserved(carbonImpact = true)
    void observedRun() {
        // never invoked: start-mode is HALTED for this check
    }
}
