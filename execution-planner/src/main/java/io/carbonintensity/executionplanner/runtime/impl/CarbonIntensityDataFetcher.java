package io.carbonintensity.executionplanner.runtime.impl;

public interface CarbonIntensityDataFetcher {

    CarbonIntensity fetchCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod);

    /**
     * Releases any background resources held by this fetcher (e.g. a background recovery poller, see
     * CIIO-470). The default implementation does nothing, so existing implementers are unaffected.
     */
    default void close() {
    }
}
