package io.carbonintensity.scheduler.micronaut.processor;

import java.util.Collections;
import java.util.List;

import io.carbonintensity.scheduler.GreenScheduled;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.TypedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Marks methods annotated with the framework-agnostic {@link GreenScheduled} annotation with the
 * {@code @GreenScheduledExecutable} marker of the runtime module at build time. The marker is
 * meta-annotated with Micronaut's {@code @Executable(processOnStartup = true)}.
 * <p>
 * As a result, Micronaut generates a reflection-free {@link io.micronaut.inject.ExecutableMethod}
 * for every {@code @GreenScheduled} method at compile time and routes it to the green scheduler on
 * startup. This is what makes the extension compatible with GraalVM native images, where runtime
 * reflection is generally not available.
 * <p>
 * The marker is referenced by name because it lives in the runtime module, which is only on the
 * application classpath and not on the annotation processor classpath.
 */
public class GreenScheduledAnnotationMapper implements TypedAnnotationMapper<GreenScheduled> {

    static final String GREEN_SCHEDULED_EXECUTABLE = "io.carbonintensity.scheduler.micronaut.GreenScheduledExecutable";

    @Override
    public Class<GreenScheduled> annotationType() {
        return GreenScheduled.class;
    }

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<GreenScheduled> annotation, VisitorContext visitorContext) {
        return Collections.singletonList(AnnotationValue.builder(GREEN_SCHEDULED_EXECUTABLE).build());
    }
}
