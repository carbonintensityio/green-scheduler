package io.carbonintensity.scheduler.micronaut;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.carbonintensity.scheduler.GreenScheduled;
import io.micronaut.context.annotation.Executable;

/**
 * Internal marker annotation added to {@link GreenScheduled} methods at build time by the
 * {@code green-scheduler-micronaut-processor} module.
 * <p>
 * The {@link Executable} meta-annotation makes Micronaut generate a reflection-free
 * {@link io.micronaut.inject.ExecutableMethod} at compile time and route the method to the
 * {@link GreenScheduledMethodProcessor} on startup. There is no need to apply this annotation
 * manually.
 */
@Retention(RUNTIME)
@Target(METHOD)
@Executable(processOnStartup = true)
public @interface GreenScheduledExecutable {

    /**
     * The fully qualified name of this annotation, used by the build time annotation mapper.
     */
    String NAME = "io.carbonintensity.scheduler.micronaut.GreenScheduledExecutable";
}
