package io.carbonintensity.scheduler.micronaut;

import java.lang.annotation.Annotation;
import java.util.Objects;

import io.carbonintensity.scheduler.ConcurrentExecution;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.SkipPredicate;
import io.micronaut.core.annotation.AnnotationValue;

/**
 * {@link GreenScheduled} backed by the Micronaut {@link AnnotationValue} that was created at build
 * time. Reading the annotation members this way avoids runtime reflection and resolves
 * {@code ${...}} property placeholders against the Micronaut environment.
 */
final class AnnotationValueGreenScheduled implements GreenScheduled {

    private final AnnotationValue<GreenScheduled> annotationValue;

    AnnotationValueGreenScheduled(AnnotationValue<GreenScheduled> annotationValue) {
        this.annotationValue = Objects.requireNonNull(annotationValue);
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return GreenScheduled.class;
    }

    @Override
    public String identity() {
        return stringMember("identity");
    }

    @Override
    public String fixedWindow() {
        return stringMember("fixedWindow");
    }

    @Override
    public String timeZone() {
        return stringMember("timeZone");
    }

    @Override
    public String dayOfMonth() {
        return stringMember("dayOfMonth");
    }

    @Override
    public String dayOfWeek() {
        return stringMember("dayOfWeek");
    }

    @Override
    public String successive() {
        return stringMember("successive");
    }

    @Override
    public String cron() {
        return stringMember("cron");
    }

    @Override
    public String duration() {
        return stringMember("duration");
    }

    @Override
    public String carbonIntensityZone() {
        return stringMember("carbonIntensityZone");
    }

    @Override
    public ConcurrentExecution concurrentExecution() {
        return annotationValue.enumValue("concurrentExecution", ConcurrentExecution.class)
                .orElse(ConcurrentExecution.PROCEED);
    }

    @Override
    public Class<? extends SkipPredicate> skipExecutionIf() {
        return annotationValue.classValue("skipExecutionIf", SkipPredicate.class)
                .orElse(SkipPredicate.Never.class);
    }

    @Override
    public String overdueGracePeriod() {
        return stringMember("overdueGracePeriod");
    }

    private String stringMember(String member) {
        return annotationValue.stringValue(member).orElse("");
    }
}
