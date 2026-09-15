package io.carbonintensity.scheduler.micronaut.processor;

import java.util.List;
import java.util.Optional;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.observability.GreenObserved;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Validates {@link GreenScheduled} business methods at build time so that invalid declarations
 * fail the compilation instead of the application startup.
 * <p>
 * Also validates the co-presence of {@link GreenObserved} with {@link GreenScheduled}. Core's own
 * {@code GreenScheduledAnnotationValidation} (invoked by Quarkus's build-time processing) never runs for
 * Micronaut, so those rules are re-implemented here, independently, at compile time.
 */
public class GreenScheduledMethodVisitor implements TypeElementVisitor<Object, Object> {

    private static final String SCHEDULED_EXECUTION = "io.carbonintensity.scheduler.ScheduledExecution";

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        List<AnnotationValue<GreenScheduled>> schedules = element.getAnnotationValuesByType(GreenScheduled.class);
        Optional<AnnotationValue<GreenObserved>> observed = element.findAnnotation(GreenObserved.class);
        if (observed.isPresent() && schedules.isEmpty()) {
            // Checked ahead of the early return below since it must fail independently of whether any
            // @GreenScheduled was found on this method.
            context.fail("@GreenObserved requires @GreenScheduled to be present on the same method", element);
        }
        if (schedules.isEmpty()) {
            return;
        }
        if (element.isPrivate()) {
            context.fail("@GreenScheduled methods must not be private", element);
        }
        if (element.isAbstract()) {
            context.fail("@GreenScheduled methods must not be abstract", element);
        }
        if (!"void".equals(element.getReturnType().getName())) {
            context.fail("@GreenScheduled methods must return void", element);
        }
        ParameterElement[] parameters = element.getParameters();
        if (parameters.length > 1
                || (parameters.length == 1 && !SCHEDULED_EXECUTION.equals(parameters[0].getType().getName()))) {
            context.fail("@GreenScheduled methods must either declare no parameters or one parameter of type "
                    + SCHEDULED_EXECUTION, element);
        }
        for (AnnotationValue<GreenScheduled> schedule : schedules) {
            validateSchedule(schedule, element, context);
        }
        observed.ifPresent(value -> validateCarbonImpactRequiresNonCronOnly(value, schedules, element, context));
    }

    /**
     * Mirrors core's {@code GreenScheduledAnnotationValidation.validateGreenObserved}: a schedule with
     * {@code carbonImpact = true} needs a carbon-aware baseline (fixedWindow or successive) to compare savings
     * against, so a plain cron-only schedule is rejected.
     */
    private void validateCarbonImpactRequiresNonCronOnly(AnnotationValue<GreenObserved> observed,
            List<AnnotationValue<GreenScheduled>> schedules, MethodElement element, VisitorContext context) {
        if (!observed.booleanValue("carbonImpact").orElse(false)) {
            return;
        }
        for (AnnotationValue<GreenScheduled> schedule : schedules) {
            String fixedWindow = schedule.stringValue("fixedWindow").orElse("");
            String successive = schedule.stringValue("successive").orElse("");
            if (containsPlaceholder(fixedWindow) || containsPlaceholder(successive)) {
                // property placeholders are resolved at runtime, nothing to validate at build time
                continue;
            }
            if (fixedWindow.isEmpty() && successive.isEmpty()) {
                context.fail("@GreenObserved(carbonImpact = true) requires the @GreenScheduled schedule to use "
                        + "fixedWindow or successive; a plain cron-only schedule has no carbon-aware baseline to "
                        + "compare savings against", element);
            }
        }
    }

    private void validateSchedule(AnnotationValue<GreenScheduled> schedule, MethodElement element, VisitorContext context) {
        String fixedWindow = schedule.stringValue("fixedWindow").orElse("");
        String successive = schedule.stringValue("successive").orElse("");
        String cron = schedule.stringValue("cron").orElse("");
        String duration = schedule.stringValue("duration").orElse("");
        if (containsPlaceholder(fixedWindow) || containsPlaceholder(successive) || containsPlaceholder(cron)
                || containsPlaceholder(duration)) {
            // property placeholders are resolved at runtime, nothing to validate at build time
            return;
        }
        if (fixedWindow.isEmpty() && successive.isEmpty() && cron.isEmpty()) {
            context.fail("@GreenScheduled requires one of fixedWindow, successive or cron to be configured", element);
        }
        if (!fixedWindow.isEmpty() && duration.isEmpty()) {
            context.fail("@GreenScheduled fixedWindow requires duration to be configured", element);
        }
    }

    private boolean containsPlaceholder(String value) {
        return value.contains("${");
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }
}
