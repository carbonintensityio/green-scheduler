package io.carbonintensity.scheduler.micronaut;

import java.util.concurrent.CompletableFuture;

import jakarta.inject.Singleton;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.impl.rest.CarbonIntensityFileApi;

/**
 * Serves carbon intensity data from the bundled test files so the integration test does not call
 * the real REST API.
 */
@Singleton
public class TestCarbonIntensityApi implements CarbonIntensityApi {

    private final CarbonIntensityFileApi delegate = new CarbonIntensityFileApi();

    @Override
    public CompletableFuture<CarbonIntensity> getCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod) {
        return delegate.getCarbonIntensity(zonedPeriod);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
