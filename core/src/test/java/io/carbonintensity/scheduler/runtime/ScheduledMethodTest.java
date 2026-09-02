package io.carbonintensity.scheduler.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ScheduledMethod#getMethodDescription()} is only reached indirectly by other tests, none
 * of which inspect the actual string it produces - PIT (CIIO-337) found the "replaced return value
 * with empty string" mutant survives as a result.
 */
class ScheduledMethodTest {

    @Test
    void getMethodDescriptionCombinesTheDeclaringClassAndMethodName() {
        ScheduledMethod method = new ImmutableScheduledMethod(mock(ScheduledInvoker.class),
                "io.carbonintensity.MyJob", "run", List.of());

        assertThat(method.getMethodDescription()).isEqualTo("io.carbonintensity.MyJob#run");
    }
}
