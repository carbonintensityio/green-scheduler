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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;

/**
 * Rest client for fetching prediction data from a remote end point.
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
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(CarbonIntensityRestApi::ensureStatusCode)
                .thenApply(HttpResponse::body)
                .thenApply(new CarbonIntensityJsonParser()::parse);
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
