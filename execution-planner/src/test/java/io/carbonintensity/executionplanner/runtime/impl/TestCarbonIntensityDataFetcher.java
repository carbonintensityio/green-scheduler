package io.carbonintensity.executionplanner.runtime.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.carbonintensity.executionplanner.planner.CarbonIntensityPeriod;
import io.carbonintensity.executionplanner.planner.Timeslot;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiConfig;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiException;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * Since CIIO-470 removed the static, fabricated fallback dataset, a failed live fetch no longer calls a
 * second "fallback" {@link CarbonIntensityApi}. Instead it either reuses a still-fresh last-known real
 * value for the zone, or - if none is available - reports a genuinely empty result, so
 * {@code FixedWindowPlanner}/{@code SuccessivePlanner} can fall back to their always-available, non-carbon
 * -aware route instead of scheduling on fabricated data.
 */
@ExtendWith(MockitoExtension.class)
class TestCarbonIntensityDataFetcher {

    ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);
    ZonedDateTime endTime = startTime.plusDays(1);
    ZonedCarbonIntensityPeriod zonedPeriod = new ZonedCarbonIntensityPeriod.Builder()
            .withStartTime(startTime)
            .withEndTime(endTime)
            .withCarbonIntensityZone("nl")
            .build();

    @Mock
    CarbonIntensityApi restApi;

    AdjustableClock clock = new AdjustableClock(startTime.toInstant());
    CarbonIntensity carbonIntensity;

    @BeforeEach
    void setUp() {
        when(restApi.isEnabled()).thenReturn(true);
        carbonIntensity = new CarbonIntensity();
        carbonIntensity.setStart(zonedPeriod.getStartTime().toInstant());
        carbonIntensity.setZone(zonedPeriod.getZone());
        carbonIntensity.setResolution(Duration.ofHours(1));
        carbonIntensity.setEnd(zonedPeriod.getEndTime().toInstant());
        carbonIntensity.getData().add(BigDecimal.valueOf(42));
    }

    private CarbonIntensityDataFetcher fetcherWithStalenessThreshold(Duration stalenessThreshold) {
        var config = new CarbonIntensityApiConfig.Builder()
                .apiKey("key")
                .apiUrl("http://localhost")
                .stalenessThreshold(stalenessThreshold)
                .build();
        return new CarbonIntensityDataFetcherImpl(restApi, config, clock);
    }

    private ZonedCarbonIntensityPeriod anotherPeriodForTheSameZone() {
        // A different window than "zonedPeriod" (a day later), so its cache key is guaranteed to be a
        // cache miss - independent of the real-time Caffeine TTL that backs CarbonIntensityCache.
        return new ZonedCarbonIntensityPeriod.Builder()
                .withStartTime(endTime)
                .withEndTime(endTime.plusDays(1))
                .withCarbonIntensityZone("nl")
                .build();
    }

    @Test
    void givenRestApi_whenFetchingData_thenCallRestApi() {
        CarbonIntensityDataFetcher dataFetcher = fetcherWithStalenessThreshold(Duration.ofHours(4));
        when(restApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(carbonIntensity));

        assertThat(dataFetcher.fetchCarbonIntensity(zonedPeriod)).isEqualTo(carbonIntensity);
    }

    @Test
    void givenRestApiReturnsDataWithoutSettingItsOwnZone_thenTheRequestedZoneIsUsedInstead() {
        // A custom CarbonIntensityApi implementation isn't guaranteed to echo the zone back onto the result
        // the way the bundled CarbonIntensityRestApi/CarbonIntensityJsonParser always does - this must not
        // crash caching or last-known-value tracking, both of which key on the zone.
        CarbonIntensityDataFetcher dataFetcher = fetcherWithStalenessThreshold(Duration.ofHours(4));
        CarbonIntensity zoneless = new CarbonIntensity();
        zoneless.setStart(zonedPeriod.getStartTime().toInstant());
        zoneless.setEnd(zonedPeriod.getEndTime().toInstant());
        zoneless.setResolution(Duration.ofHours(1));
        zoneless.setData(List.of(BigDecimal.valueOf(42)));
        when(restApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(zoneless));

        var result = dataFetcher.fetchCarbonIntensity(zonedPeriod);

        assertThat(result.getZone()).isEqualTo("nl");
    }

    @Test
    void givenRestApiFails_andNoLastKnownValueYet_thenReturnsEmptyResultInsteadOfFabricatedData() {
        CarbonIntensityDataFetcher dataFetcher = fetcherWithStalenessThreshold(Duration.ofHours(4));
        when(restApi.getCarbonIntensity(zonedPeriod))
                .thenReturn(CompletableFuture.failedFuture(new CarbonIntensityApiException("Failure intentionally.")));

        var result = dataFetcher.fetchCarbonIntensity(zonedPeriod);

        assertThat(result.hasData()).isFalse();
    }

    @Test
    void givenRestApiFails_butAFreshLastKnownValueExists_thenReusesItAsAGenuineDecision() {
        CarbonIntensityDataFetcher dataFetcher = fetcherWithStalenessThreshold(Duration.ofHours(4));
        when(restApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(carbonIntensity));
        assertThat(dataFetcher.fetchCarbonIntensity(zonedPeriod)).isEqualTo(carbonIntensity);

        ZonedCarbonIntensityPeriod nextPeriod = anotherPeriodForTheSameZone();
        when(restApi.getCarbonIntensity(nextPeriod))
                .thenReturn(CompletableFuture.failedFuture(new CarbonIntensityApiException("Failure intentionally.")));
        clock.advance(Duration.ofMinutes(30));

        var result = dataFetcher.fetchCarbonIntensity(nextPeriod);

        assertThat(result.hasData()).isTrue();
        // CIIO-475 follow-up: repeated hourly across the full 24h window (not one giant, whole-window
        // bucket) so a candidate slot aligned to it gets 42 back verbatim rather than a diluted fraction -
        // see synthesizeFromLastKnownValue's Javadoc for why that distinction matters.
        assertThat(result.getResolution()).isEqualTo(Duration.ofHours(1));
        assertThat(result.getData()).hasSize(24).containsOnly(BigDecimal.valueOf(42));
        assertThat(result.getZone()).isEqualTo("nl");
    }

    /**
     * CIIO-475 follow-up: reproduces the pilot's exact daily-rollover scenario for {@code nl-fixed-window}
     * (2026-09-18T02:00 CEST) - a live fetch for the new day's window fails outright (404, not retried),
     * while a fresh last-known real reading exists from an earlier successful fetch for the same zone. An
     * interior 1-hour candidate slot - {@code FixedWindowPlanner}'s own hardcoded step, applied to a 1-hour
     * job, matching {@code nl-fixed-window} - must get that real reading back undiluted, never a fabricated
     * or diluted zero, once run through the exact same arithmetic {@code FixedWindowPlanner} uses.
     * <p>
     * Deliberately an interior slot, not the window's very first or last candidate: those touch this
     * fixture's own boundary at a single instant, which only {@code Timeslot}'s CIIO-475 overlap fix (a
     * separate, already-merged PR on the {@code ciio-475} branch, not present here) distinguishes from a
     * real overlap - exercising that here would conflate two independently-owned fixes.
     */
    @Test
    void givenATotalFetchFailureAtADayBoundary_thenTheReusedLastKnownValueSurvivesThePlannersArithmeticUndiluted() {
        CarbonIntensityDataFetcher dataFetcher = fetcherWithStalenessThreshold(Duration.ofHours(4));
        CarbonIntensity realReading = new CarbonIntensity();
        realReading.setStart(zonedPeriod.getStartTime().toInstant());
        realReading.setEnd(zonedPeriod.getEndTime().toInstant());
        realReading.setZone("nl");
        realReading.setResolution(Duration.ofHours(1));
        realReading.setData(List.of(new BigDecimal("45.2")));
        when(restApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(realReading));
        dataFetcher.fetchCarbonIntensity(zonedPeriod); // records 45.2 as the last-known value for "nl"

        ZonedCarbonIntensityPeriod newDaysWindow = anotherPeriodForTheSameZone();
        when(restApi.getCarbonIntensity(newDaysWindow))
                .thenReturn(CompletableFuture.failedFuture(new CarbonIntensityApiException("404 - not published yet")));
        clock.advance(Duration.ofMinutes(1));

        CarbonIntensity synthesized = dataFetcher.fetchCarbonIntensity(newDaysWindow);
        assertThat(synthesized.hasData()).isTrue();

        ZonedDateTime windowStart = newDaysWindow.getStartTime();
        ZonedDateTime candidateStart = windowStart.plusHours(5);
        ZonedDateTime candidateEnd = candidateStart.plusHours(1);
        List<CarbonIntensityPeriod> periods = CarbonIntensityPeriod.of(synthesized);

        BigDecimal chosen = Timeslot.calculateCarbonIntensity(periods, candidateStart, candidateEnd);

        assertThat(chosen).isNotEqualByComparingTo(BigDecimal.ZERO);
        assertThat(chosen).isEqualByComparingTo("45.2");
    }

    @Test
    void givenRestApiFails_andLastKnownValueIsOlderThanStalenessThreshold_thenReturnsEmptyResult() {
        CarbonIntensityDataFetcher dataFetcher = fetcherWithStalenessThreshold(Duration.ofMinutes(10));
        when(restApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(carbonIntensity));
        assertThat(dataFetcher.fetchCarbonIntensity(zonedPeriod)).isEqualTo(carbonIntensity);

        ZonedCarbonIntensityPeriod nextPeriod = anotherPeriodForTheSameZone();
        when(restApi.getCarbonIntensity(nextPeriod))
                .thenReturn(CompletableFuture.failedFuture(new CarbonIntensityApiException("Failure intentionally.")));
        clock.advance(Duration.ofMinutes(15));

        var result = dataFetcher.fetchCarbonIntensity(nextPeriod);

        assertThat(result.hasData()).isFalse();
    }

    @SuppressWarnings("deprecation")
    @Test
    void givenDeprecatedTwoArgConstructor_thenFallbackApiParameterIsIgnoredAndFetcherStillWorks() {
        CarbonIntensityApi ignoredFallback = mock(CarbonIntensityApi.class);
        CarbonIntensityDataFetcher dataFetcher = new CarbonIntensityDataFetcherImpl(restApi, ignoredFallback);
        when(restApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(carbonIntensity));

        assertThat(dataFetcher.fetchCarbonIntensity(zonedPeriod)).isEqualTo(carbonIntensity);
        verifyNoInteractions(ignoredFallback);
    }

    /**
     * A {@link Clock} whose {@link #instant()} can be moved forward on demand, so tests can simulate time
     * passing (for staleness checks) without actually waiting.
     */
    static final class AdjustableClock extends Clock {
        private Instant now;

        AdjustableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
