package io.carbonintensity.executionplanner.runtime.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiConfig;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * Fetches carbon-intensity data for a given zone/period, on the scheduler's critical path.
 * <p>
 * Historically, any failure to reach the real REST API fell back to a bundled, static per-zone dataset
 * (see CIIO-470) - which returned unrealistic, fabricated numbers and made several zones share the exact
 * same fabricated value. That fallback dataset has been removed entirely. Instead, on failure this class:
 * <ol>
 * <li>relies on {@code CarbonIntensityRestApi}'s own short, bounded retry for transient connectivity
 * errors (see {@link CarbonIntensityApiConfig});</li>
 * <li>reuses the {@link LastKnownIntensityCache best-known real value} for the zone, as long as it is not
 * older than {@link CarbonIntensityApiConfig#getStalenessThreshold()};</li>
 * <li>otherwise returns a genuinely empty {@link CarbonIntensity} (no data), so that
 * {@code FixedWindowPlanner}/{@code SuccessivePlanner}'s {@code canSchedule} can let the caller fall
 * through to the scheduler's existing, always-available fallback route (fixed cron midpoint / plain
 * interval) instead of silently reporting a fabricated "green" moment.</li>
 * </ol>
 * A {@link BackgroundCarbonIntensityRefresher} keeps retrying a failed zone off this critical path, so the
 * last-known value recovers as soon as the API is reachable again.
 */
public class CarbonIntensityDataFetcherImpl implements CarbonIntensityDataFetcher {

    private static final Logger logger = LoggerFactory.getLogger(CarbonIntensityDataFetcherImpl.class);

    private final CarbonIntensityCache cache = new CarbonIntensityCache();
    private final LastKnownIntensityCache lastKnown = new LastKnownIntensityCache();
    private final CarbonIntensityApi restApi;
    private final CarbonIntensityApiConfig config;
    private final Clock clock;
    private final BackgroundCarbonIntensityRefresher backgroundRefresher;

    public CarbonIntensityDataFetcherImpl(CarbonIntensityApi restApi, CarbonIntensityApiConfig config) {
        this(restApi, config, Clock.systemUTC());
    }

    public CarbonIntensityDataFetcherImpl(CarbonIntensityApi restApi, CarbonIntensityApiConfig config, Clock clock) {
        this.restApi = restApi;
        this.config = config;
        this.clock = clock;
        if (!restApi.isEnabled()) {
            logger.warn("Rest API not configured. No live carbon intensity data can be fetched; scheduling will use "
                    + "the configured fallback (fixed-window cron midpoint / plain interval) until it is.");
        }
        this.backgroundRefresher = new BackgroundCarbonIntensityRefresher(restApi, lastKnown, clock);
    }

    /**
     * @deprecated since CIIO-470: the file-based static fallback dataset this constructor relied on
     *             returned fabricated, unrealistic intensity values instead of a genuine "no data" signal,
     *             and has been removed. The {@code fallbackApi} parameter is now ignored. Use
     *             {@link #CarbonIntensityDataFetcherImpl(CarbonIntensityApi, CarbonIntensityApiConfig)}
     *             instead, and let the scheduler's existing, always-available fallback route (fixed-window
     *             cron midpoint / plain interval) take over when no genuine data is available.
     */
    @Deprecated(since = "0.8.7", forRemoval = true)
    public CarbonIntensityDataFetcherImpl(CarbonIntensityApi restApi, CarbonIntensityApi fallbackApi) {
        this(restApi, new CarbonIntensityApiConfig.Builder().build());
        if (fallbackApi != null) {
            logger.warn("The fallbackApi parameter of this deprecated constructor is ignored - CIIO-470 removed the "
                    + "static fallback dataset it relied on. See CarbonIntensityDataFetcherImpl's Javadoc.");
        }
    }

    public CarbonIntensity fetchCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod) {
        logger.trace("Fetching data for zone {}", zonedPeriod);
        Optional<CarbonIntensity> cached = getFromCache(zonedPeriod);
        if (cached.isPresent()) {
            CarbonIntensity value = cached.get();
            if (value.hasData()) {
                logger.trace("Found carbonIntensity data in cache");
                return value;
            }
            // Negative-cached: a live fetch for this exact period recently failed and, by design, we don't
            // retry it again before CarbonIntensityCache's TTL for empty values expires. A still-fresh
            // last-known value (kept warm by the background refresher) is nonetheless a genuine decision.
            return reuseLastKnownOr(zonedPeriod, value);
        }

        logger.debug("Empty cache, fetching data from rest API {}", zonedPeriod);
        CarbonIntensity fetched;
        try {
            fetched = restApi.getCarbonIntensity(zonedPeriod).join();
        } catch (RuntimeException e) {
            logger.warn("Failed to get live carbon intensity data for zone {} after retries", zonedPeriod.getZone(), e);
            backgroundRefresher.ensureRefreshing(zonedPeriod);
            CarbonIntensity empty = storeInCache(emptyResult(zonedPeriod));
            return reuseLastKnownOr(zonedPeriod, empty);
        }

        // The zone we asked for is authoritative, regardless of whether a given CarbonIntensityApi
        // implementation bothers to echo it back onto the result (the bundled CarbonIntensityRestApi always
        // does, via CarbonIntensityJsonParser, but a custom/test implementation may not) - every downstream
        // use here (caching, last-known-value tracking) needs a non-null zone to key on.
        fetched.setZone(zonedPeriod.getZone());

        recordLastKnownValue(fetched);
        return storeInCache(fetched);
    }

    private CarbonIntensity reuseLastKnownOr(ZonedCarbonIntensityPeriod zonedPeriod, CarbonIntensity empty) {
        return lastKnown.get(zonedPeriod.getZone(), clock.instant(), config.getStalenessThreshold())
                .map(value -> synthesizeFromLastKnownValue(zonedPeriod, value))
                .orElse(empty);
    }

    private void recordLastKnownValue(CarbonIntensity fetched) {
        if (fetched.hasData()) {
            Instant now = clock.instant();
            lastKnown.record(fetched.getZone(), representativeValue(fetched, now), now);
        }
    }

    /**
     * Picks a single representative value out of a full {@link CarbonIntensity} result to remember as "the"
     * last-known value for its zone: the data point covering {@code at} if it falls within the result's
     * range, otherwise the first data point.
     */
    static BigDecimal representativeValue(CarbonIntensity carbonIntensity, Instant at) {
        List<BigDecimal> data = carbonIntensity.getData();
        Duration resolution = carbonIntensity.getResolution();
        Instant start = carbonIntensity.getStart();
        if (resolution != null && !resolution.isZero() && !resolution.isNegative() && start != null) {
            long index = Duration.between(start, at).dividedBy(resolution);
            if (index >= 0 && index < data.size()) {
                return data.get((int) index);
            }
        }
        return data.get(0);
    }

    /**
     * Builds a synthetic result for {@code zonedPeriod} that applies a single last-known value uniformly
     * across the whole requested window. There is no way to rank sub-slots within the window without real
     * granular data, so every slot in it reports the same intensity and the planner effectively picks the
     * earliest one - which is the honest thing to do with a single stale reading.
     */
    private static CarbonIntensity synthesizeFromLastKnownValue(ZonedCarbonIntensityPeriod zonedPeriod, BigDecimal value) {
        Instant start = zonedPeriod.getStartTime().toInstant();
        Instant end = zonedPeriod.getEndTime().toInstant();
        Duration span = Duration.between(start, end);

        CarbonIntensity result = new CarbonIntensity();
        result.setZone(zonedPeriod.getZone());
        result.setStart(start);
        result.setEnd(end);
        result.setResolution(span.isZero() || span.isNegative() ? Duration.ofMinutes(30) : span);
        result.setData(new ArrayList<>(List.of(value)));
        return result;
    }

    private static CarbonIntensity emptyResult(ZonedCarbonIntensityPeriod zonedPeriod) {
        CarbonIntensity result = new CarbonIntensity();
        result.setZone(zonedPeriod.getZone());
        result.setStart(zonedPeriod.getStartTime().toInstant());
        result.setEnd(zonedPeriod.getEndTime().toInstant());
        result.setData(new ArrayList<>());
        return result;
    }

    private Optional<CarbonIntensity> getFromCache(ZonedCarbonIntensityPeriod zonedPeriod) {
        var start = zonedPeriod.getStartTime().toInstant();
        return cache.get(new CarbonIntensityCache.Key(start, zonedPeriod.getZone()));
    }

    private CarbonIntensity storeInCache(CarbonIntensity carbonIntensity) {
        var start = carbonIntensity.getStart();
        return cache.put(new CarbonIntensityCache.Key(start, carbonIntensity.getZone()), carbonIntensity);
    }

    @Override
    public void close() {
        backgroundRefresher.close();
    }
}
