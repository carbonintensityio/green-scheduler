package io.carbonintensity.scheduler.micronaut;

import java.lang.annotation.Annotation;
import java.util.Objects;

import io.carbonintensity.scheduler.observability.GreenObserved;
import io.micronaut.core.annotation.AnnotationValue;

/**
 * {@link GreenObserved} backed by the Micronaut {@link AnnotationValue} that was created at build time. Mirrors
 * {@link AnnotationValueGreenScheduled}: reading the single {@code carbonImpact} member this way avoids runtime
 * reflection.
 */
final class AnnotationValueGreenObserved implements GreenObserved {

    private final AnnotationValue<GreenObserved> annotationValue;

    AnnotationValueGreenObserved(AnnotationValue<GreenObserved> annotationValue) {
        this.annotationValue = Objects.requireNonNull(annotationValue);
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return GreenObserved.class;
    }

    @Override
    public boolean carbonImpact() {
        return annotationValue.booleanValue("carbonImpact").orElse(false);
    }
}
