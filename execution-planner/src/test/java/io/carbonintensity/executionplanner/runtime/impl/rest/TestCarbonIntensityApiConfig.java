package io.carbonintensity.executionplanner.runtime.impl.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TestCarbonIntensityApiConfig {

    CarbonIntensityApiConfig.Builder builder;

    @BeforeEach
    public void setup() {
        builder = new CarbonIntensityApiConfig.Builder();
    }

    @Test
    void testApiKey() {
        var config = builder
                .apiKey("apiKey")
                .build();

        assertThat(config.getApiKey()).isEqualTo("apiKey");
    }

    @Test
    void testApiUrl() {
        assertThat(builder
                .apiUrl("baseUrl")
                .build()
                .getApiUrl()).isEqualTo("baseUrl");
    }

    @ParameterizedTest
    @MethodSource("testEnabledParameters")
    void testEnabled(String apiKey, String apiUrl, boolean enabled) {
        assertThat(builder
                .apiKey(apiKey)
                .apiUrl(apiUrl)
                .build()
                .isEnabled())
                .isEqualTo(enabled);
    }

    private static Stream<Arguments> testEnabledParameters() {
        return Stream.of(
                Arguments.of(null, null, false),
                Arguments.of("", "", false),
                Arguments.of("null", null, false),
                Arguments.of(null, "null", false),
                Arguments.of("null", "null", true));
    }

    @Test
    void whenApiKeyAndApiUrlIsSet_thenEnableApi() {
        assertThat(builder
                .apiKey(null)
                .apiUrl("baseUrl")
                .build().isEnabled()).isFalse();
    }

    @Test
    void whenRetryAndStalenessFieldsAreNotSet_thenDefaultsFromCIIO470Apply() {
        var config = builder.build();

        assertThat(config.getRetryMaxAttempts()).isEqualTo(CarbonIntensityApiConfig.DEFAULT_RETRY_MAX_ATTEMPTS)
                .isEqualTo(3);
        assertThat(config.getRetryInitialBackoff()).isEqualTo(CarbonIntensityApiConfig.DEFAULT_RETRY_INITIAL_BACKOFF)
                .isEqualTo(Duration.ofMillis(300));
        assertThat(config.getRetryBackoffMultiplier()).isEqualTo(CarbonIntensityApiConfig.DEFAULT_RETRY_BACKOFF_MULTIPLIER)
                .isEqualTo(3.0);
        assertThat(config.getRetryBudget()).isEqualTo(CarbonIntensityApiConfig.DEFAULT_RETRY_BUDGET)
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(config.getStalenessThreshold()).isEqualTo(CarbonIntensityApiConfig.DEFAULT_STALENESS_THRESHOLD)
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    void whenRetryAndStalenessFieldsAreExplicitlySet_thenTheyOverrideTheDefaults() {
        var config = builder
                .retryMaxAttempts(1)
                .retryInitialBackoff(Duration.ofMillis(50))
                .retryBackoffMultiplier(2.0)
                .retryBudget(Duration.ofMillis(500))
                .stalenessThreshold(Duration.ofHours(6))
                .build();

        assertThat(config.getRetryMaxAttempts()).isEqualTo(1);
        assertThat(config.getRetryInitialBackoff()).isEqualTo(Duration.ofMillis(50));
        assertThat(config.getRetryBackoffMultiplier()).isEqualTo(2.0);
        assertThat(config.getRetryBudget()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.getStalenessThreshold()).isEqualTo(Duration.ofHours(6));
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1 })
    void whenRetryMaxAttemptsIsLessThanOne_thenThrowException(int invalid) {
        assertThrows(IllegalArgumentException.class, () -> builder.retryMaxAttempts(invalid));
    }

    @ParameterizedTest
    @ValueSource(doubles = { 0.0, 0.99, -1.0 })
    void whenRetryBackoffMultiplierIsLessThanOne_thenThrowException(double invalid) {
        assertThrows(IllegalArgumentException.class, () -> builder.retryBackoffMultiplier(invalid));
    }

}
