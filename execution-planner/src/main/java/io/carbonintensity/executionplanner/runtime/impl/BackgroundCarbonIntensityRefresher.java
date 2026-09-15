package io.carbonintensity.executionplanner.runtime.impl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * Keeps retrying a failed zone off the scheduler's critical path, so {@link LastKnownIntensityCache}
 * recovers as soon as the upstream API is reachable again - instead of only refreshing whenever some job
 * happens to trigger another foreground fetch.
 * <p>
 * See CIIO-470: {@link CarbonIntensityCache} caches a "no data" result for a whole hour
 * ({@code DEFAULT_TTL_EMPTY_VALUES}, "when we get no data, we retry in one hour"). Without this
 * background poller, a real outage lasting only a few seconds could otherwise leave scheduling
 * non-carbon-aware for up to an hour, purely because of that unrelated negative-cache TTL.
 * <p>
 * This never blocks a trigger evaluation: {@link #ensureRefreshing} only schedules a background task and
 * returns immediately, deduplicated per zone so a zone that is already being polled is left alone.
 */
class BackgroundCarbonIntensityRefresher implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BackgroundCarbonIntensityRefresher.class);

    /** How long to wait between background recovery attempts for a zone. */
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(30);
    /** Give up polling a zone after this many failed attempts, until the next foreground failure retriggers it. */
    static final int DEFAULT_MAX_ATTEMPTS = 20;

    private final CarbonIntensityApi restApi;
    private final LastKnownIntensityCache lastKnown;
    private final Clock clock;
    private final Duration pollInterval;
    private final int maxAttempts;
    private final ScheduledExecutorService executor;
    private final Set<String> zonesBeingRecovered = ConcurrentHashMap.newKeySet();

    BackgroundCarbonIntensityRefresher(CarbonIntensityApi restApi, LastKnownIntensityCache lastKnown, Clock clock) {
        this(restApi, lastKnown, clock, DEFAULT_POLL_INTERVAL, DEFAULT_MAX_ATTEMPTS);
    }

    BackgroundCarbonIntensityRefresher(CarbonIntensityApi restApi, LastKnownIntensityCache lastKnown, Clock clock,
            Duration pollInterval, int maxAttempts) {
        this.restApi = restApi;
        this.lastKnown = lastKnown;
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.maxAttempts = maxAttempts;
        this.executor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "green-scheduler-carbon-intensity-recovery-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Starts polling {@code zonedPeriod}'s zone in the background if it isn't already being polled. A
     * no-op if a recovery attempt for that zone is already in flight.
     */
    void ensureRefreshing(ZonedCarbonIntensityPeriod zonedPeriod) {
        String zone = zonedPeriod.getZone();
        if (!zonesBeingRecovered.add(zone)) {
            return;
        }
        logger.debug("Starting background carbon-intensity recovery for zone {}", zone);
        scheduleNextAttempt(zonedPeriod, 1);
    }

    private void scheduleNextAttempt(ZonedCarbonIntensityPeriod zonedPeriod, int attemptNumber) {
        try {
            executor.schedule(() -> attemptRecovery(zonedPeriod, attemptNumber), pollInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // executor was shut down concurrently (e.g. scheduler shutting down) - nothing left to recover for
            zonesBeingRecovered.remove(zonedPeriod.getZone());
        }
    }

    private void attemptRecovery(ZonedCarbonIntensityPeriod zonedPeriod, int attemptNumber) {
        String zone = zonedPeriod.getZone();
        try {
            CarbonIntensity result = restApi.getCarbonIntensity(zonedPeriod).join();
            if (result.hasData()) {
                Instant now = clock.instant();
                BigDecimal value = CarbonIntensityDataFetcherImpl.representativeValue(result, now);
                lastKnown.record(zone, value, now);
                logger.info("CarbonIntensity API reachable again for zone {}, refreshed last-known value", zone);
                zonesBeingRecovered.remove(zone);
                return;
            }
        } catch (RuntimeException e) {
            logger.trace("Background carbon-intensity recovery attempt {} for zone {} still failing", attemptNumber, zone, e);
        }

        if (attemptNumber >= maxAttempts) {
            logger.debug("Giving up background carbon-intensity recovery for zone {} after {} attempts; "
                    + "a subsequent foreground failure will restart it", zone, attemptNumber);
            zonesBeingRecovered.remove(zone);
            return;
        }
        scheduleNextAttempt(zonedPeriod, attemptNumber + 1);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
