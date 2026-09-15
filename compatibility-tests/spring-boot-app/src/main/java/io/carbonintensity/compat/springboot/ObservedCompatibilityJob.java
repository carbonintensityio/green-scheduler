package io.carbonintensity.compat.springboot;

import org.springframework.stereotype.Component;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;

/**
 * A minimal {@code @GreenObserved} job, giving autoconfiguration a schedule to pick up and export
 * {@code green.scheduler.job.*} metrics for, once a {@code MeterRegistry} bean is present - see CIIO-349.
 */
@Component
public class ObservedCompatibilityJob {

    @GreenScheduled(identity = "observed-compat-check", successive = "0H 1H 2H", duration = "PT5M", carbonIntensityZone = "NL")
    @GreenObserved(carbonImpact = true)
    void run() {
        // never invoked: start-mode is HALTED for this check
    }
}
