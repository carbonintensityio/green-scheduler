package io.carbonintensity.executionplanner.runtime.impl;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

public class CarbonIntensityCache {

    public static final Duration DEFAULT_TTL_EMPTY_VALUES = Duration.ofHours(1);
    private final Duration emptyValueTTL;
    private final Cache<Key, CarbonIntensity> caffeine;

    /**
     * Creates the cache with a default TTL for empty values.
     */
    public CarbonIntensityCache() {
        this(DEFAULT_TTL_EMPTY_VALUES);
    }

    /**
     * Creates the cache with given TTL for empty values.
     *
     * @param emptyValueTTL Time to live for empty values.
     */
    public CarbonIntensityCache(Duration emptyValueTTL) {
        this.emptyValueTTL = emptyValueTTL;
        this.caffeine = createCache();
    }

    public Optional<CarbonIntensity> get(Key key) {
        return Optional.ofNullable(caffeine.getIfPresent(key));
    }

    public CarbonIntensity put(Key key, CarbonIntensity value) {
        caffeine.put(key, value);
        return value;
    }

    private Cache<Key, CarbonIntensity> createCache() {
        return Caffeine.newBuilder()
                // expire when carbon intensity data becomes useless.
                // when we get no data, we retry in one hour.
                .expireAfter(new Expiry<Key, CarbonIntensity>() {
                    @Override
                    public long expireAfterCreate(Key key, CarbonIntensity value, long currentTime) {
                        if (value.getData().isEmpty()) {
                            return emptyValueTTL.toNanos();
                        }
                        Instant current = Instant.ofEpochSecond(0L, currentTime);

                        // expire endTime of day.
                        var expirationTime = value.getEnd().plusSeconds(1);
                        return Duration.between(current, expirationTime).toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(Key key, CarbonIntensity value, long currentTime,
                            long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(Key key, CarbonIntensity value, long currentTime,
                            long currentDuration) {
                        return currentDuration;
                    }
                }).build();
    }

    public static class Key {
        private final Instant time;
        private final String zone;

        public Key(Instant time, String zone) {
            this.time = time.truncatedTo(ChronoUnit.HOURS);
            this.zone = zone.toLowerCase().trim();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Key key = (Key) o;
            return Objects.equals(time, key.time) && Objects.equals(zone, key.zone);
        }

        @Override
        public int hashCode() {
            return Objects.hash(time, zone);
        }
    }
}
