package io.carbonintensity.executionplanner.runtime.impl.rest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * Rest client for fetching prediction data from a remote end point.
 * <p>
 * A single {@link #getCarbonIntensity(ZonedCarbonIntensityPeriod)} call retries a short, bounded number of
 * times (see {@link CarbonIntensityApiConfig}) - but only on a transient, connection-level failure
 * ({@link TransientConnectivityErrors}), never on a real HTTP error response. This is deliberately kept
 * short: {@code SimpleScheduler.checkTriggers()} in {@code core} evaluates every registered job serially on
 * a shared, small thread pool and blocks on this call, so a long retry here would delay every other job's
 * trigger evaluation, not just the affected one. See CIIO-470.
 */
public class CarbonIntensityRestApi implements CarbonIntensityApi {

    /**
     * Endpoint url format: {baseUrl}/api/carbonintensity/zone/{zone}/{date}/{apiType}?tz={timeZone}
     */
    private static final String ENDPOINT_TEMPLATE = "%s/api/carbonintensity/zone/%s/%s/%s?tz=%s";
    private static final Logger logger = LoggerFactory.getLogger(CarbonIntensityRestApi.class);

    private final CarbonIntensityApiConfig config;
    private final HttpClient httpClient;
    private final CarbonIntensityApiType carbonIntensityApiType;
    private final CarbonIntensityJsonParser jsonParser = new CarbonIntensityJsonParser();

    public CarbonIntensityRestApi(CarbonIntensityApiConfig config, CarbonIntensityApiType carbonIntensityApiType) {
        this.config = config;
        this.carbonIntensityApiType = carbonIntensityApiType;
        this.httpClient = createHttpClient();
    }

    public CarbonIntensityRestApi(CarbonIntensityApiConfig config, HttpClient httpClient,
            CarbonIntensityApiType carbonIntensityApiType) {
        this.config = config;
        this.httpClient = httpClient;
        this.carbonIntensityApiType = carbonIntensityApiType;
    }

    private static <T> HttpResponse<T> ensureStatusCode(HttpResponse<T> response) {
        if (response.statusCode() != 200) {
            throw new CarbonIntensityApiException("Failed to get carbonintensity data. Error: " + response.statusCode());
        }
        return response;
    }

    @Override
    public CompletableFuture<CarbonIntensity> getCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod) {
        if (config.getApiUrl() == null || config.getApiUrl().isEmpty()) {
            return CompletableFuture.failedFuture(new CarbonIntensityApiException("Base url not set."));
        }
        var uri = getUri(zonedPeriod.getStartTime(), zonedPeriod.getZone());
        logger.debug("Requesting url {}", uri);
        var request = createRequest(uri);
        long deadlineNanos = System.nanoTime() + config.getRetryBudget().toNanos();
        return attempt(request, 1, deadlineNanos)
                .orTimeout(config.getRetryBudget().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * @param attemptNumber 1-based number of this attempt
     */
    private CompletableFuture<CarbonIntensity> attempt(HttpRequest request, int attemptNumber, long deadlineNanos) {
        return sendOnce(request).exceptionallyCompose(error -> retryOrFail(request, attemptNumber, deadlineNanos, error));
    }

    private CompletableFuture<CarbonIntensity> retryOrFail(HttpRequest request, int attemptNumber, long deadlineNanos,
            Throwable error) {
        Throwable cause = TransientConnectivityErrors.unwrap(error);
        if (attemptNumber >= config.getRetryMaxAttempts() || !TransientConnectivityErrors.isTransient(cause)) {
            return CompletableFuture.failedFuture(error);
        }

        long delayMillis = RetryBackoff.delayMillis(attemptNumber, config.getRetryInitialBackoff(),
                config.getRetryBackoffMultiplier());
        if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis) >= deadlineNanos) {
            logger.debug("Not retrying CarbonIntensity API call: the {} retry budget would be exceeded",
                    config.getRetryBudget());
            return CompletableFuture.failedFuture(error);
        }

        logger.debug("Transient error contacting CarbonIntensity API (attempt {}/{}), retrying in {} ms",
                attemptNumber, config.getRetryMaxAttempts(), delayMillis, cause);
        return CompletableFuture
                .supplyAsync(() -> null, CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> attempt(request, attemptNumber + 1, deadlineNanos));
    }

    private CompletableFuture<CarbonIntensity> sendOnce(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(CarbonIntensityRestApi::ensureStatusCode)
                .thenApply(HttpResponse::body)
                .thenApply(jsonParser::parse);
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofMinutes(1))
                .build();
    }

    private URI getUri(ZonedDateTime startTime, String zone) {
        var dateText = startTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        var timezoneText = URLEncoder.encode(startTime.getZone().getId(), StandardCharsets.UTF_8);
        var endpoint = String.format(ENDPOINT_TEMPLATE,
                config.getApiUrl(),
                zone,
                dateText,
                carbonIntensityApiType.getApiPath(),
                timezoneText);
        return URI.create(endpoint);
    }

    private HttpRequest createRequest(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Authorization", "APIKey " + config.getApiKey())
                .GET()
                .build();
    }

    public String getApiName() {
        return "CarbonIntensityIO";
    }

}
