package io.carbonintensity.scheduler.micronaut.processor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.carbonintensity.scheduler.GreenScheduled;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Unit tests for the build-time validation performed by {@link GreenScheduledMethodVisitor},
 * exercised directly against mocked {@link MethodElement}/{@link VisitorContext} instances rather
 * than through a full annotation-processing compilation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GreenScheduledMethodVisitorTest {

    private static final String SCHEDULED_EXECUTION = "io.carbonintensity.scheduler.ScheduledExecution";

    private final GreenScheduledMethodVisitor visitor = new GreenScheduledMethodVisitor();

    @Mock
    private MethodElement method;
    @Mock
    private VisitorContext context;
    @Mock
    private ClassElement voidReturnType;

    private void givenValidMethodShape() {
        when(method.isPrivate()).thenReturn(false);
        when(method.isAbstract()).thenReturn(false);
        when(voidReturnType.getName()).thenReturn("void");
        when(method.getReturnType()).thenReturn(voidReturnType);
        when(method.getParameters()).thenReturn(new ParameterElement[0]);
    }

    private AnnotationValue<GreenScheduled> schedule(String fixedWindow, String successive, String cron, String duration) {
        var builder = AnnotationValue.builder(GreenScheduled.class);
        if (fixedWindow != null) {
            builder.member("fixedWindow", fixedWindow);
        }
        if (successive != null) {
            builder.member("successive", successive);
        }
        if (cron != null) {
            builder.member("cron", cron);
        }
        if (duration != null) {
            builder.member("duration", duration);
        }
        return builder.build();
    }

    @Test
    void methodsWithoutGreenScheduledAreIgnored() {
        when(method.getAnnotationValuesByType(GreenScheduled.class)).thenReturn(List.of());

        visitor.visitMethod(method, context);

        verifyNoInteractions(context);
    }

    @Test
    void privateMethodFailsValidation() {
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("08:00 17:00", null, null, "1h")));
        when(method.isPrivate()).thenReturn(true);
        when(voidReturnType.getName()).thenReturn("void");
        when(method.getReturnType()).thenReturn(voidReturnType);
        when(method.getParameters()).thenReturn(new ParameterElement[0]);

        visitor.visitMethod(method, context);

        verify(context).fail(eq("@GreenScheduled methods must not be private"), eq(method));
    }

    @Test
    void abstractMethodFailsValidation() {
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("08:00 17:00", null, null, "1h")));
        when(method.isPrivate()).thenReturn(false);
        when(method.isAbstract()).thenReturn(true);
        when(voidReturnType.getName()).thenReturn("void");
        when(method.getReturnType()).thenReturn(voidReturnType);
        when(method.getParameters()).thenReturn(new ParameterElement[0]);

        visitor.visitMethod(method, context);

        verify(context).fail(eq("@GreenScheduled methods must not be abstract"), eq(method));
    }

    @Test
    void nonVoidReturnTypeFailsValidation() {
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("08:00 17:00", null, null, "1h")));
        when(method.isPrivate()).thenReturn(false);
        when(method.isAbstract()).thenReturn(false);
        when(voidReturnType.getName()).thenReturn("java.lang.String");
        when(method.getReturnType()).thenReturn(voidReturnType);
        when(method.getParameters()).thenReturn(new ParameterElement[0]);

        visitor.visitMethod(method, context);

        verify(context).fail(eq("@GreenScheduled methods must return void"), eq(method));
    }

    @Test
    void tooManyParametersFailsValidation() {
        givenValidMethodShape();
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("08:00 17:00", null, null, "1h")));
        ParameterElement first = mockParameterOfType(SCHEDULED_EXECUTION);
        ParameterElement second = mockParameterOfType(SCHEDULED_EXECUTION);
        when(method.getParameters()).thenReturn(new ParameterElement[] { first, second });

        visitor.visitMethod(method, context);

        verify(context).fail(
                eq("@GreenScheduled methods must either declare no parameters or one parameter of type "
                        + SCHEDULED_EXECUTION),
                eq(method));
    }

    @Test
    void singleParameterOfWrongTypeFailsValidation() {
        givenValidMethodShape();
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("08:00 17:00", null, null, "1h")));
        ParameterElement wrongType = mockParameterOfType("java.lang.String");
        when(method.getParameters()).thenReturn(new ParameterElement[] { wrongType });

        visitor.visitMethod(method, context);

        verify(context).fail(
                eq("@GreenScheduled methods must either declare no parameters or one parameter of type "
                        + SCHEDULED_EXECUTION),
                eq(method));
    }

    @Test
    void singleParameterOfScheduledExecutionTypeIsValid() {
        givenValidMethodShape();
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("08:00 17:00", null, null, "1h")));
        ParameterElement correctType = mockParameterOfType(SCHEDULED_EXECUTION);
        when(method.getParameters()).thenReturn(new ParameterElement[] { correctType });

        visitor.visitMethod(method, context);

        verify(context, never()).fail(any(), any());
    }

    @Test
    void noWindowConfiguredFailsValidation() {
        givenValidMethodShape();
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule(null, null, null, null)));

        visitor.visitMethod(method, context);

        verify(context).fail(
                eq("@GreenScheduled requires one of fixedWindow, successive or cron to be configured"), eq(method));
    }

    @Test
    void fixedWindowWithoutDurationFailsValidation() {
        givenValidMethodShape();
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("08:00 17:00", null, null, null)));

        visitor.visitMethod(method, context);

        verify(context).fail(eq("@GreenScheduled fixedWindow requires duration to be configured"), eq(method));
    }

    @Test
    void successiveWindowWithoutFixedWindowIsValid() {
        givenValidMethodShape();
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule(null, "3h 1h 4h", null, "PT30M")));

        visitor.visitMethod(method, context);

        verify(context, never()).fail(any(), any());
    }

    @Test
    void placeholdersSkipValidationEntirely() {
        givenValidMethodShape();
        when(method.getAnnotationValuesByType(GreenScheduled.class))
                .thenReturn(List.of(schedule("${green-scheduler.window}", null, null, null)));

        visitor.visitMethod(method, context);

        verify(context, never()).fail(any(), any());
    }

    private ParameterElement mockParameterOfType(String typeName) {
        ParameterElement parameter = org.mockito.Mockito.mock(ParameterElement.class);
        ClassElement type = org.mockito.Mockito.mock(ClassElement.class);
        when(type.getName()).thenReturn(typeName);
        when(parameter.getType()).thenReturn(type);
        return parameter;
    }
}
