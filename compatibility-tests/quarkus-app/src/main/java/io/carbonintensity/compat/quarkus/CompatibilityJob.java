package io.carbonintensity.compat.quarkus;

import jakarta.enterprise.context.ApplicationScoped;

import io.carbonintensity.scheduler.GreenScheduled;

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
}
