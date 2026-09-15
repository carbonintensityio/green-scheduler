package io.carbonintensity.scheduler.spring.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.spring.TestObservedScheduledJob;
import io.carbonintensity.scheduler.spring.TestScheduledJob;

class ScheduledMethodFactoryTests {

    TestScheduledJob testJob = new TestScheduledJob();
    ScheduledMethodFactory factory = new ScheduledMethodFactory();
    Method method;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        method = TestScheduledJob.class.getMethod("run");
    }

    @Test
    void testCreate() {
        var scheduledMethod = factory.create(testJob, method);
        assertThat(scheduledMethod).isNotNull();
        assertThat(scheduledMethod.getMethodName()).isEqualTo("run");
        assertThat(scheduledMethod.getDeclaringClassName()).isEqualTo(TestScheduledJob.class.getName());
        assertThat(scheduledMethod.getSchedules()).hasSize(1);
        assertThat(scheduledMethod.getGreenObserved()).isEmpty();
    }

    @Test
    void testGetGreenScheduledAnnotations() {
        var scheduledMethod = ScheduledMethodFactory.getGreenScheduledAnnotations(method);
        assertThat(scheduledMethod)
                .isNotNull()
                .hasSize(1);
    }

    @Test
    void givenGreenObservedMethod_whenCreate_thenGreenObservedIsCarriedThrough() throws NoSuchMethodException {
        var observedJob = new TestObservedScheduledJob();
        var observedMethod = TestObservedScheduledJob.class.getMethod("run");

        var scheduledMethod = factory.create(observedJob, observedMethod);

        assertThat(scheduledMethod.getGreenObserved())
                .isPresent()
                .get()
                .satisfies(observed -> assertThat(observed.carbonImpact()).isTrue());
    }

    @Test
    void givenPlainScheduledMethod_whenGetGreenObservedAnnotation_thenReturnNull() {
        assertThat(ScheduledMethodFactory.getGreenObservedAnnotation(method)).isNull();
    }

}
