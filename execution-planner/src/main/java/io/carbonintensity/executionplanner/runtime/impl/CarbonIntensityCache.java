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
                        Instant current = Instant.ofEpochSecond(0L, currentTime);
                        return computeExpireAfterCreateNanos(value, current, emptyValueTTL);
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

    /**
     * Computes the nanosecond TTL that {@code expireAfterCreate} hands to Caffeine, as a standalone pure
     * function so it can be property-tested directly.
     * <p>
     * Clamps to zero instead of returning a negative value: if {@code value.getEnd()} already lies in the
     * past relative to {@code currentTime} (e.g. a stale entry re-created after being retrieved from a slow
     * upstream call), {@code Duration.between(...)} is negative, and Caffeine's {@link Expiry} contract
     * treats a negative return value as undefined behaviour. Zero tells Caffeine to expire the entry
     * immediately, which is the correct outcome for already-stale data.
     */
    static long computeExpireAfterCreateNanos(CarbonIntensity value, Instant currentTime, Duration emptyValueTTL) {
        if (value.getData().isEmpty()) {
            return emptyValueTTL.toNanos();
        }

        // expire endTime of day.
        var expirationTime = value.getEnd().plusSeconds(1);
        return Math.max(0L, Duration.between(currentTime, expirationTime).toNanos());
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
