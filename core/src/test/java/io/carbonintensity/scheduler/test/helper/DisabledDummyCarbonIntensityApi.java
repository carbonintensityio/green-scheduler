package io.carbonintensity.scheduler.test.helper;

import java.util.concurrent.CompletableFuture;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiException;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * A {@link CarbonIntensityApi} test double simulating a genuinely unusable/unreachable API: every call
 * fails, and {@link #isEnabled()} reports {@code false}. Since CIIO-470, a failure here is expected to end
 * up as "no usable data" (see {@code CarbonIntensityDataFetcherImpl}), so the scheduler falls through to
 * its always-available fallback route (fixed-window cron midpoint / plain interval) - not, as before, a
 * fabricated fallback dataset.
 */
public class DisabledDummyCarbonIntensityApi implements CarbonIntensityApi {

    @Override
    public CompletableFuture<CarbonIntensity> getCarbonIntensity(ZonedCarbonIntensityPeriod zonedCarbonIntensityPeriod) {
        return CompletableFuture.failedFuture(new CarbonIntensityApiException("Disabled dummy API - no data available"));
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
