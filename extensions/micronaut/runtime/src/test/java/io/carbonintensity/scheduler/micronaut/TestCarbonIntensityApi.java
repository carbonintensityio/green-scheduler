package io.carbonintensity.scheduler.micronaut;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Singleton;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * Serves fixed, in-memory test data so the integration test does not call the real REST API. It no longer
 * delegates to the bundled fallback dataset (removed as part of CIIO-470).
 */
@Singleton
public class TestCarbonIntensityApi implements CarbonIntensityApi {

    @Override
    public CompletableFuture<CarbonIntensity> getCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod) {
        var carbonIntensity = new CarbonIntensity();
        carbonIntensity.setZone(zonedPeriod.getZone());
        carbonIntensity.setStart(zonedPeriod.getStartTime().toInstant());
        carbonIntensity.setEnd(zonedPeriod.getEndTime().toInstant());
        carbonIntensity.setResolution(Duration.between(zonedPeriod.getStartTime(), zonedPeriod.getEndTime()));
        carbonIntensity.setData(List.of(BigDecimal.valueOf(42)));
        return CompletableFuture.completedFuture(carbonIntensity);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
