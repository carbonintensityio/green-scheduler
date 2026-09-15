package io.carbonintensity.compat.micronaut;

import jakarta.inject.Singleton;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

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

    /**
     * Exercises {@link GreenObserved} (CIIO-350) against the requested {@code micronaut.version}: the compile-time
     * co-presence check in {@code GreenScheduledMethodVisitor} must let this compile, and
     * {@code GreenScheduledMethodProcessor} must pass the annotation through to the scheduled job without a
     * {@code MeterRegistry} bean on the classpath (this app never adds Micrometer).
     */
    @GreenScheduled(identity = "compat-observed-check", fixedWindow = "08:00 17:00", duration = "PT5M", carbonIntensityZone = "NL")
    @GreenObserved(carbonImpact = true)
    void observedRun() {
        // never invoked: start-mode is HALTED for this check
    }
}
