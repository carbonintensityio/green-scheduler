package io.carbonintensity.scheduler.runtime.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.runtime.ScheduledMethod;

/**
 * {@link SchedulerContextImpl#getScheduledMethods()} is only reached indirectly, never with its
 * result inspected - PIT (CIIO-337) found the "replaced return value with an empty list" mutant
 * survives as a result.
 */
class SchedulerContextImplTest {

    @Test
    void getScheduledMethodsReturnsExactlyWhatWasPassedToTheConstructor() {
        ScheduledMethod methodA = mock(ScheduledMethod.class);
        ScheduledMethod methodB = mock(ScheduledMethod.class);
        SchedulerContextImpl context = new SchedulerContextImpl(List.of(methodA, methodB));

        assertThat(context.getScheduledMethods()).containsExactly(methodA, methodB);
    }
}
