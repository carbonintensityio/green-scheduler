package io.carbonintensity.compat.micronaut;

import jakarta.inject.Singleton;

import io.carbonintensity.scheduler.GreenScheduled;

/**
 * A minimal scheduled job, just like a real consumer would write. Its only purpose here is to give
 * the Micronaut build an {@link GreenScheduled} method to process, so the extension's compile-time
 * annotation processing actually runs.
 */
@Singleton
public class CompatibilityJob {

    @GreenScheduled(identity = "compat-check", successive = "0H 1H 2H", duration = "PT5M", carbonIntensityZone = "NL")
    void run() {
        // never invoked: start-mode is HALTED for this check
    }
}
