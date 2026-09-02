package io.carbonintensity.scheduler.micronaut.processor;

import java.util.Collections;
import java.util.List;

import io.carbonintensity.scheduler.GreenScheduled;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.TypedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Same as {@link GreenScheduledAnnotationMapper} but for the repeatable container
 * {@link GreenScheduled.GreenSchedules}, so that methods declaring multiple
 * {@code @GreenScheduled} annotations are also marked executable at build time.
 */
public class GreenSchedulesAnnotationMapper implements TypedAnnotationMapper<GreenScheduled.GreenSchedules> {

    @Override
    public Class<GreenScheduled.GreenSchedules> annotationType() {
        return GreenScheduled.GreenSchedules.class;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<GreenScheduled.GreenSchedules> annotation,
            VisitorContext visitorContext) {
        return Collections.singletonList(
                AnnotationValue.builder(GreenScheduledAnnotationMapper.GREEN_SCHEDULED_EXECUTABLE).build());
    }
}
