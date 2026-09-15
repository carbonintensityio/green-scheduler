package io.carbonintensity.scheduler.test.helper;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiException;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityJsonParser;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * A deterministic, file-backed {@link CarbonIntensityApi} test double, so scheduling tests can assert
 * "the greenest slot within this window is at this specific time" against known, fixed values.
 * <p>
 * This mirrors the lookup scheme the production code used to have via {@code CarbonIntensityFileApi} and a
 * bundled dataset under {@code fallback/} - both removed in CIIO-470, because a real deployment silently
 * substituting fabricated numbers for missing data is a production bug, not a feature. Here, in a test, an
 * explicit, known-in-advance dataset used to assert scheduling behaviour is exactly the right tool - it is
 * simply no longer allowed to also be the thing a real REST API failure quietly falls back to. See
 * {@code CarbonIntensityDataFetcherImpl}'s Javadoc for what a real failure does instead.
 */
public class FixtureCarbonIntensityApi implements CarbonIntensityApi {

    private static final String BASE_DIRECTORY = "carbon-intensity-fixtures";
    private final CarbonIntensityJsonParser jsonParser = new CarbonIntensityJsonParser();

    private static String getTimezone(ZonedDateTime startTime) {
        return startTime.getZone()
                .normalized()
                .getId()
                .toLowerCase()
                .replace("/", "-");
    }

    @Override
    public CompletableFuture<CarbonIntensity> getCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod) {
        var zone = zonedPeriod.getZone().toLowerCase();
        var timezone = getTimezone(zonedPeriod.getStartTime());

        try {
            var resource = getJsonFileUrl(zone, timezone);
            var carbonIntensity = parseJsonFile(zonedPeriod, resource);
            return CompletableFuture.completedFuture(carbonIntensity);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new CarbonIntensityApiException(e));
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private CarbonIntensity parseJsonFile(ZonedCarbonIntensityPeriod zonedPeriod, URL jsonFilePath) throws IOException {
        var carbonIntensity = jsonParser.parse(jsonFilePath.openStream());
        enrichData(zonedPeriod, carbonIntensity);
        return carbonIntensity;
    }

    private static void enrichData(ZonedCarbonIntensityPeriod zonedPeriod, CarbonIntensity carbonIntensity) {
        carbonIntensity.setStart(truncateToHours(zonedPeriod.getStartTime()));
        carbonIntensity.setEnd(truncateToHours(zonedPeriod.getEndTime()));
        carbonIntensity.setZone(zonedPeriod.getZone());
    }

    private static Instant truncateToHours(ZonedDateTime zonedPeriod) {
        return zonedPeriod.toInstant().truncatedTo(ChronoUnit.HOURS);
    }

    private URL getJsonFileUrl(String zone, String timezone) throws IOException {
        return Stream.of(
                getResource(String.format("/%s/%s/%s.json", BASE_DIRECTORY, zone, timezone)),
                getResource(String.format("/%s/%s/z.json", BASE_DIRECTORY, zone)),
                getResource(String.format("/%s/z.json", BASE_DIRECTORY)))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "No matching fixture file found for carbonIntensityZone [" + zone + "] and timezone [" + timezone
                                + "]"));
    }

    private URL getResource(String resourceName) {
        return this.getClass().getResource(resourceName);
    }
}
