package io.carbonintensity.executionplanner.runtime.impl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the most recently, successfully fetched <em>real</em> carbon-intensity value per zone.
 * <p>
 * This exists so a brief outage of the CarbonIntensity REST API doesn't have to be treated as "no data
 * whatsoever": reusing a still-fresh real reading (see {@link #get}) is a genuine, if slightly stale,
 * carbon-aware decision - unlike the static, fabricated fallback dataset this replaces (CIIO-470). Once a
 * value is older than the caller-supplied staleness threshold, {@link #get} refuses to hand it out, and the
 * caller must treat the zone as having no usable data.
 */
public class LastKnownIntensityCache {

    private final Map<String, Entry> lastKnownByZone = new ConcurrentHashMap<>();

    /**
     * Records {@code value} as the most recently observed real reading for {@code zone}, overwriting
     * whatever was recorded before.
     */
    public void record(String zone, BigDecimal value, Instant fetchedAt) {
        lastKnownByZone.put(normalize(zone), new Entry(value, fetchedAt));
    }

    /**
     * @return the last recorded value for {@code zone}, unless either nothing was ever recorded or the
     *         recorded value is older than {@code stalenessThreshold} relative to {@code now}
     */
    public Optional<BigDecimal> get(String zone, Instant now, Duration stalenessThreshold) {
        Entry entry = lastKnownByZone.get(normalize(zone));
        if (entry == null) {
            return Optional.empty();
        }
        Duration age = Duration.between(entry.fetchedAt, now);
        if (age.compareTo(stalenessThreshold) > 0) {
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    private static String normalize(String zone) {
        return zone.toLowerCase(Locale.ROOT).trim();
    }

    private record Entry(BigDecimal value, Instant fetchedAt) {
    }
}
