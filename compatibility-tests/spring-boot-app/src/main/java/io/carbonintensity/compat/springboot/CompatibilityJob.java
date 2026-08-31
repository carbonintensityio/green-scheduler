package io.carbonintensity.compat.springboot;

import org.springframework.stereotype.Component;

import io.carbonintensity.scheduler.GreenScheduled;

/**
 * A minimal scheduled job, just like a real consumer would write. Its only purpose here is to give
 * autoconfiguration a {@link GreenScheduled} method to pick up and schedule.
 */
@Component
public class CompatibilityJob {

    @GreenScheduled(identity = "compat-check", successive = "0H 1H 2H", duration = "PT5M", carbonIntensityZone = "NL")
    void run() {
        // never invoked: start-mode is HALTED for this check
    }
}
