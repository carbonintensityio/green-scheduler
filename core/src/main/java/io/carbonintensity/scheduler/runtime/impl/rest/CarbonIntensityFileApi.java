package io.carbonintensity.scheduler.runtime.impl.rest;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiException;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityJsonParser;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * This implementation gets data from file system. Each carbonIntensityZone has a directory with dataset
 * for each timezone.
 */
public class CarbonIntensityFileApi implements CarbonIntensityApi {

    private static final Logger logger = LoggerFactory.getLogger(CarbonIntensityFileApi.class);
    private static final String BASE_DIRECTORY = "fallback";
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
        logger.debug("Getting fallback data for carbonIntensityZone {} and timezone {}", zone, timezone);

        try {
            var resource = getJsonFileUrl(zone, timezone);
            var carbonIntensity = parseJsonFile(zonedPeriod, resource);
            return CompletableFuture.completedFuture(carbonIntensity);
        } catch (IOException e) {
            logger.error("Failed to get data", e);
            return CompletableFuture.failedFuture(new CarbonIntensityApiException(e));
        }
    }

    @Override
    public boolean isEnabled() {
        return Files.isDirectory(Paths.get(BASE_DIRECTORY));
    }

    private CarbonIntensity parseJsonFile(ZonedCarbonIntensityPeriod zonedPeriod, URL jsonFilePath) throws IOException {
        var carbonIntensity = jsonParser.parse(jsonFilePath.openStream());
        enrichData(zonedPeriod, carbonIntensity);
        return carbonIntensity;
    }

    private static void enrichData(ZonedCarbonIntensityPeriod zonedPeriod, CarbonIntensity carbonIntensity) {
        var start = truncateToHours(zonedPeriod.getStartTime());
        var end = truncateToHours(zonedPeriod.getEndTime());
        carbonIntensity.setStart(start);
        carbonIntensity.setEnd(end);
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
                        "No matching file found for carbonIntensityZone [" + zone + "] and timezone [" + timezone + "]"));
    }

    private URL getResource(String resourceName) {
        return this.getClass().getResource(resourceName);
    }
}
