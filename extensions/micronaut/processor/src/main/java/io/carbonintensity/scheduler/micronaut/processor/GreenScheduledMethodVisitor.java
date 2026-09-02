package io.carbonintensity.scheduler.micronaut.processor;

import java.util.List;

import io.carbonintensity.scheduler.GreenScheduled;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Validates {@link GreenScheduled} business methods at build time so that invalid declarations
 * fail the compilation instead of the application startup.
 */
public class GreenScheduledMethodVisitor implements TypeElementVisitor<Object, Object> {

    private static final String SCHEDULED_EXECUTION = "io.carbonintensity.scheduler.ScheduledExecution";

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        List<AnnotationValue<GreenScheduled>> schedules = element.getAnnotationValuesByType(GreenScheduled.class);
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
