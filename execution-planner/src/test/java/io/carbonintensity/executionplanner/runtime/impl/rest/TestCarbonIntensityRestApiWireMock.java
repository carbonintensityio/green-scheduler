package io.carbonintensity.executionplanner.runtime.impl.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;

/**
 * Integration tests exercising the real HTTP call made by {@link CarbonIntensityRestApi} through WireMock -
 * as opposed to {@link TestCarbonIntensityRestApi}, which mocks the {@code HttpClient} interface itself and
 * so can never exercise the retry loop's exception-classification logic against a real connection failure.
 * <p>
 * See CIIO-470: retries only a transient, connection-level failure (WireMock fault injection), never a real
 * HTTP error status, and a non-default configuration (a single scenario using {@code retryMaxAttempts(1)})
 * proves that the configured values actually drive the behaviour, not just the defaults.
 */
class TestCarbonIntensityRestApiWireMock {

    private static final String PATH_PATTERN = "/api/carbonintensity/zone/NL/.*";

    private WireMockServer wireMockServer;
    private ZonedCarbonIntensityPeriod zonedPeriod;

    @BeforeEach
    void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);
        zonedPeriod = new ZonedCarbonIntensityPeriod.Builder()
                .withStartTime(startTime)
                .withEndTime(startTime.plusDays(1))
                .withCarbonIntensityZone("NL")
                .build();
    }

    @AfterEach
    void stopWireMock() {
        wireMockServer.stop();
    }

    private CarbonIntensityApiConfig.Builder configBuilder() {
        return new CarbonIntensityApiConfig.Builder()
                .apiKey("test-api-key")
                .apiUrl("http://localhost:" + wireMockServer.port());
    }

    private static String successBody() {
        var carbonIntensity = new CarbonIntensity();
        carbonIntensity.setZone("NL");
        carbonIntensity.setStart(Instant.parse("2025-01-01T00:00:00Z"));
        carbonIntensity.setEnd(Instant.parse("2025-01-02T00:00:00Z"));
        carbonIntensity.setResolution(Duration.ofHours(1));
        carbonIntensity.setData(List.of(BigDecimal.valueOf(45), BigDecimal.valueOf(52)));
        return new CarbonIntensityJsonParser().toJson(carbonIntensity);
    }

    @Test
    void aSuccessfulResponseOnTheFirstAttemptCompletesWithoutAnyRetry() {
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN)).willReturn(ok(successBody())));

        var restApi = new CarbonIntensityRestApi(configBuilder().build(), CarbonIntensityApiType.PREDICTED);

        var result = restApi.getCarbonIntensity(zonedPeriod).join();

        assertThat(result.hasData()).isTrue();
        wireMockServer.verify(1, getRequestedFor(urlPathMatching(PATH_PATTERN)));
    }

    @Test
    void aTransientConnectivityFailureIsRetriedUntilItSucceeds() {
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN))
                .inScenario("recovering-api")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("second-attempt"));
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN))
                .inScenario("recovering-api")
                .whenScenarioStateIs("second-attempt")
                .willReturn(ok(successBody())));

        var config = configBuilder().build();
        var restApi = new CarbonIntensityRestApi(config, CarbonIntensityApiType.PREDICTED);

        var result = restApi.getCarbonIntensity(zonedPeriod).join();

        assertThat(result.hasData()).isTrue();
        wireMockServer.verify(2, getRequestedFor(urlPathMatching(PATH_PATTERN)));
    }

    @Test
    void aPersistentTransientConnectivityFailureIsRetriedUpToTheConfiguredMaxAttemptsThenFails() {
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        var config = configBuilder()
                .retryMaxAttempts(3)
                .retryInitialBackoff(Duration.ofMillis(20))
                .retryBackoffMultiplier(2.0)
                .build();
        var restApi = new CarbonIntensityRestApi(config, CarbonIntensityApiType.PREDICTED);

        assertThatThrownBy(() -> restApi.getCarbonIntensity(zonedPeriod).get())
                .isInstanceOf(ExecutionException.class);
        wireMockServer.verify(3, getRequestedFor(urlPathMatching(PATH_PATTERN)));
    }

    @Test
    void aRealHttpErrorResponseIsNeverRetried() {
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN)).willReturn(aResponse().withStatus(500)));

        var restApi = new CarbonIntensityRestApi(configBuilder().build(), CarbonIntensityApiType.PREDICTED);

        assertThatThrownBy(() -> restApi.getCarbonIntensity(zonedPeriod).get())
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(CarbonIntensityApiException.class);
        wireMockServer.verify(1, getRequestedFor(urlPathMatching(PATH_PATTERN)));
    }

    @Test
    void aRealHttpNotFoundResponseIsNeverRetriedEither() {
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN)).willReturn(aResponse().withStatus(404)));

        var restApi = new CarbonIntensityRestApi(configBuilder().build(), CarbonIntensityApiType.PREDICTED);

        assertThatThrownBy(() -> restApi.getCarbonIntensity(zonedPeriod).get())
                .isInstanceOf(ExecutionException.class);
        wireMockServer.verify(1, getRequestedFor(urlPathMatching(PATH_PATTERN)));
    }

    @Test
    void aNonDefaultConfigurationOfMaxAttemptsOneMeansNoRetryEvenOnATransientFailure() {
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // Proves the configuration is actually wired through, not just its default: with the default of 3
        // attempts, the earlier "persistent failure" test above observes 3 requests for the exact same
        // always-failing stub; here, only 1.
        var config = configBuilder().retryMaxAttempts(1).build();
        var restApi = new CarbonIntensityRestApi(config, CarbonIntensityApiType.PREDICTED);

        assertThatThrownBy(() -> restApi.getCarbonIntensity(zonedPeriod).get())
                .isInstanceOf(ExecutionException.class);
        wireMockServer.verify(1, getRequestedFor(urlPathMatching(PATH_PATTERN)));
    }

    @Test
    void aTinyRetryBudgetCutsOffFurtherAttemptsEvenBelowMaxAttempts() {
        wireMockServer.stubFor(get(urlPathMatching(PATH_PATTERN))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // A backoff of 5 seconds before the second attempt, but a budget of only 50ms: there isn't enough
        // budget left to even schedule that retry, so this must fail after exactly 1 request.
        var config = configBuilder()
                .retryMaxAttempts(3)
                .retryInitialBackoff(Duration.ofSeconds(5))
                .retryBudget(Duration.ofMillis(50))
                .build();
        var restApi = new CarbonIntensityRestApi(config, CarbonIntensityApiType.PREDICTED);

        assertThatThrownBy(() -> restApi.getCarbonIntensity(zonedPeriod).get())
                .isInstanceOf(ExecutionException.class);
        wireMockServer.verify(1, getRequestedFor(urlPathMatching(PATH_PATTERN)));
    }
}
