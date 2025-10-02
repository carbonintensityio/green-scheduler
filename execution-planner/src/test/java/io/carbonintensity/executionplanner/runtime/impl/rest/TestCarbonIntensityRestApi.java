package io.carbonintensity.executionplanner.runtime.impl.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;

@ExtendWith(MockitoExtension.class)
class TestCarbonIntensityRestApi {

    CarbonIntensityRestApi restApi;

    @Mock
    HttpClient httpClient;

    @Mock
    HttpResponse<InputStream> httpResponse;

    ZonedDateTime startTime = ZonedDateTime.now();
    ZonedDateTime endTime = startTime.plusHours(24);
    String zone = "NL";
    ZonedCarbonIntensityPeriod zonedPeriod;
    CarbonIntensityApiConfig config;
    InputStream stream;

    @BeforeEach
    void setup() {
        zonedPeriod = new ZonedCarbonIntensityPeriod.Builder()
                .withStartTime(startTime)
                .withEndTime(endTime)
                .withCarbonIntensityZone(zone)
                .build();

        config = new CarbonIntensityApiConfig.Builder()
                .apiKey("apiKey")
                .apiUrl("http://localhost")
                .build();

        restApi = new CarbonIntensityRestApi(config, httpClient, CarbonIntensityApiType.PREDICTED);
        stream = getClass().getResourceAsStream("/day-ahead-20240824-Z.json");
    }

    @Test
    void givenValidResponse_whenBodyIsValid_thenCompleteSuccessfully() throws Exception {
        when(httpClient.sendAsync(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<InputStream>> any()))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(stream);

        var carbonIntensity = restApi.getCarbonIntensity(zonedPeriod);
        assertThat(carbonIntensity).isCompleted();
        assertThat(carbonIntensity.get()).isNotNull();

        verify(httpClient).sendAsync(any(HttpRequest.class), any());
        verify(httpResponse, atLeast(1)).statusCode();
        verify(httpResponse).body();
    }

    @Test
    void givenHttpResponse_whenStatusOtherThan200_thenCompletedExceptionally() {
        when(httpClient.sendAsync(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<InputStream>> any()))
                .thenReturn(CompletableFuture.completedFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(403);

        var carbonIntensity = restApi.getCarbonIntensity(zonedPeriod);
        assertThat(carbonIntensity).isCompletedExceptionally();
        assertThrows(ExecutionException.class, carbonIntensity::get);

        verify(httpClient).sendAsync(any(HttpRequest.class), Mockito.<HttpResponse.BodyHandler<InputStream>> any());
        verify(httpResponse, atLeast(1)).statusCode();
        verify(httpResponse, never()).body();
    }
}
