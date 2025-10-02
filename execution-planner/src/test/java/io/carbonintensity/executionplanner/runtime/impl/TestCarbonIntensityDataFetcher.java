package io.carbonintensity.executionplanner.runtime.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityApiException;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

@ExtendWith(MockitoExtension.class)
class TestCarbonIntensityDataFetcher {

    ZonedDateTime startTime = ZonedDateTime.now();
    ZonedDateTime endTime = startTime.plusDays(1);
    ZonedCarbonIntensityPeriod zonedPeriod = new ZonedCarbonIntensityPeriod.Builder()
            .withStartTime(startTime)
            .withEndTime(endTime)
            .withCarbonIntensityZone("nl")
            .build();

    @Mock
    CarbonIntensityApi restApi;

    @Mock
    CarbonIntensityApi fallbackApi;

    CarbonIntensityDataFetcher dataFetcher;
    CarbonIntensity carbonIntensity;

    @BeforeEach
    public void setUp() {
        when(restApi.isEnabled()).thenReturn(true);
        dataFetcher = new CarbonIntensityDataFetcherImpl(restApi, fallbackApi);
        carbonIntensity = new CarbonIntensity();
        carbonIntensity.setStart(zonedPeriod.getStartTime().toInstant());
        carbonIntensity.setZone(zonedPeriod.getZone());
        carbonIntensity.setResolution(Duration.ofHours(1));
        carbonIntensity.setEnd(zonedPeriod.getEndTime().toInstant());
        carbonIntensity.getData().add(BigDecimal.valueOf(1));
    }

    @Test
    void givenRestApi_whenFetchingData_thenCallRestApi() {
        when(restApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(carbonIntensity));
        assertThat(dataFetcher.fetchCarbonIntensity(zonedPeriod)).isEqualTo(carbonIntensity);
        verify(fallbackApi, never()).getCarbonIntensity(zonedPeriod);
    }

    @Test
    void givenRestApi_whenRestApiFails_thenCallFallbackApi() {
        when(restApi.getCarbonIntensity(zonedPeriod))
                .thenReturn(CompletableFuture.failedFuture(new CarbonIntensityApiException("Failure intentionally.")));
        when(fallbackApi.getCarbonIntensity(zonedPeriod)).thenReturn(CompletableFuture.completedFuture(carbonIntensity));
        assertThat(dataFetcher.fetchCarbonIntensity(zonedPeriod)).isEqualTo(carbonIntensity);
        verify(fallbackApi).getCarbonIntensity(zonedPeriod);
    }

}
