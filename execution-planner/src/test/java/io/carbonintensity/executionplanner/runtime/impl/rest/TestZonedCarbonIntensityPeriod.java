package io.carbonintensity.executionplanner.runtime.impl.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;

class TestZonedCarbonIntensityPeriod {

    ZonedCarbonIntensityPeriod.Builder builder;

    @BeforeEach
    void setup() {
        builder = new ZonedCarbonIntensityPeriod.Builder();
    }

    @Test
    void whenBuildingWithoutZone_thenThrowException() {
        assertThrows(NullPointerException.class, () -> {
            builder = new ZonedCarbonIntensityPeriod.Builder();
            builder.withEndTime(ZonedDateTime.now());
            builder.withStartTime(ZonedDateTime.now());
            builder.build();
        });
    }

    @Test
    void whenBuildWithoutStartTime_thenThrowException() {
        assertThrows(NullPointerException.class, () -> {
            builder = new ZonedCarbonIntensityPeriod.Builder();
            builder.withEndTime(ZonedDateTime.now());
            builder.withCarbonIntensityZone("nl");
            builder.build();
        });
    }

    @Test
    void whenBuildWithoutEndTime_thenThrowException() {
        assertThrows(NullPointerException.class, () -> {
            builder = new ZonedCarbonIntensityPeriod.Builder();
            builder.withStartTime(ZonedDateTime.now());
            builder.withCarbonIntensityZone("nl");
            builder.build();
        });
    }

    @Test
    void whenBuildingMultipleTime_thenReturnUniqueInstances() {
        builder.withEndTime(ZonedDateTime.now());
        builder.withStartTime(ZonedDateTime.now());
        builder.withCarbonIntensityZone("nl");
        var zonedPeriod = builder.build();
        assertThat(zonedPeriod).isNotNull();
        assertThat(builder.build()).isNotEqualTo(zonedPeriod);
    }
}
