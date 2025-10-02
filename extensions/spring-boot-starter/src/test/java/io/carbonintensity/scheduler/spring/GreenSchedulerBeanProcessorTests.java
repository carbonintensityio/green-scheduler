package io.carbonintensity.scheduler.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GreenSchedulerBeanProcessorTests {

    final String beanName = "testBean";
    final Object bean = new TestScheduledJob();
    GreenSchedulerBeanProcessor beanProcessor;
    Method runMethod;

    @BeforeEach
    void setup() throws NoSuchMethodException {
        beanProcessor = new GreenSchedulerBeanProcessor();
        runMethod = TestScheduledJob.class.getMethod("run");
    }

    @Test
    void givenAnnotatedBean_whenProcessing_thenRegisterBean() {
        beanProcessor.postProcessBeforeInitialization(bean, beanName);
        var beanInfoList = beanProcessor.getScheduledBeanInfoList();
        assertThat(beanInfoList)
                .hasSize(1)
                .first()
                .usingRecursiveAssertion()
                .isEqualTo(new GreenSchedulerBeanInfo(bean, runMethod));
    }

    @Test
    void givenAnnotatedBean_whenProcessingMultipleTimes_thenRegisterBeanOnlyOnce() {
        beanProcessor.postProcessBeforeInitialization(bean, beanName);
        beanProcessor.postProcessBeforeInitialization(bean, beanName);
        var beanInfoList = beanProcessor.getScheduledBeanInfoList();
        assertThat(beanInfoList)
                .hasSize(1)
                .first()
                .usingRecursiveComparison()
                .isEqualTo(new GreenSchedulerBeanInfo(bean, runMethod));
    }

    @Test
    void givenAnnotatedBean_whenProcessing_thenIterateValues() {
        beanProcessor.postProcessBeforeInitialization(bean, beanName);
        assertThat(beanProcessor.hasNext()).isTrue();
        var beanInfo = beanProcessor.next();
        assertThat(beanInfo.getBean()).isEqualTo(bean);
        assertThat(beanInfo.getBeanMethod()).isEqualTo(runMethod);
        assertThat(beanProcessor.hasNext()).isFalse();
    }

}
