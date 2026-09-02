package io.carbonintensity.scheduler.micronaut.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.GreenScheduled;
import io.micronaut.core.annotation.AnnotationValue;

class GreenScheduledAnnotationMapperTest {

    private final GreenScheduledAnnotationMapper mapper = new GreenScheduledAnnotationMapper();

    @Test
    void annotationTypeIsGreenScheduled() {
        assertThat(mapper.annotationType()).isEqualTo(GreenScheduled.class);
    }

    @Test
    void mapsToTheGreenScheduledExecutableMarker() {
        AnnotationValue<GreenScheduled> annotation = AnnotationValue.builder(GreenScheduled.class)
                .member("fixedWindow", "08:00 17:00")
                .member("duration", "1h")
                .build();

        List<AnnotationValue<?>> mapped = mapper.map(annotation, null);

        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).getAnnotationName())
                .isEqualTo("io.carbonintensity.scheduler.micronaut.GreenScheduledExecutable");
    }
}
