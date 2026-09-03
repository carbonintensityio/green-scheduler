package io.carbonintensity.scheduler.micronaut.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.GreenScheduled;
import io.micronaut.core.annotation.AnnotationValue;

class GreenSchedulesAnnotationMapperTest {

    private final GreenSchedulesAnnotationMapper mapper = new GreenSchedulesAnnotationMapper();

    @Test
    void annotationTypeIsGreenSchedules() {
        assertThat(mapper.annotationType()).isEqualTo(GreenScheduled.GreenSchedules.class);
    }

    @Test
    void mapsToTheSameGreenScheduledExecutableMarkerAsTheSingleAnnotationMapper() {
        AnnotationValue<GreenScheduled.GreenSchedules> annotation = AnnotationValue
                .builder(GreenScheduled.GreenSchedules.class)
                .build();

        List<AnnotationValue<?>> mapped = mapper.map(annotation, null);

        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0).getAnnotationName())
                .isEqualTo(GreenScheduledAnnotationMapper.GREEN_SCHEDULED_EXECUTABLE);
    }
}
