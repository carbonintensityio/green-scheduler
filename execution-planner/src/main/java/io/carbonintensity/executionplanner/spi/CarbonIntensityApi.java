package io.carbonintensity.executionplanner.spi;

import java.util.concurrent.CompletableFuture;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;

/**
 * Interface for CarbonIntensity API.
 */
public interface CarbonIntensityApi {

    /**
     * Gets carbon intensity data for the given period and zone
     *
     * @param zonedPeriod period and zone
     * @return data for the given period
     */
    CompletableFuture<CarbonIntensity> getCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod);

    boolean isEnabled();

    /**
     * Returns the name of the API implementation.
     * <p>
     * By default, this returns the simple class name of the implementing class.
     * Override this method to provide a more descriptive or user-friendly name.
     *
     * @return the name of the API
     */
    default String getApiName() {
        return this.getClass().getSimpleName();
    }
}
