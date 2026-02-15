package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.quarkus.common.runtime.ScheduledMethod;
import io.carbonintensity.scheduler.quarkus.common.runtime.SchedulerContext;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.NonBlocking;

class ScheduledInvokerBlockingTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addClasses(Jobs.class).addAsResource("application.properties"));

    @Inject
    SchedulerContext schedulerContext;

    @Test
    void nonBlockingInvokerIsReportedCorrectly() {
        List<ScheduledMethod> scheduledMethods = schedulerContext.getScheduledMethods();
        ScheduledMethod blocking = scheduledMethods.stream()
                .filter(method -> method.getMethodName().equals("blockingJob"))
                .findFirst()
                .orElseThrow();
        ScheduledMethod nonBlocking = scheduledMethods.stream()
                .filter(method -> method.getMethodName().equals("nonBlockingJob"))
                .findFirst()
                .orElseThrow();

        assertTrue(schedulerContext.createInvoker(blocking.getInvokerClassName()).isBlocking());
        assertFalse(schedulerContext.createInvoker(nonBlocking.getInvokerClassName()).isBlocking());
    }

    @ApplicationScoped
    static class Jobs {

        @GreenScheduled(identity = "blocking", successive = "0s 1s 1s", duration = "1m", carbonIntensityZone = "NL")
        void blockingJob() {
        }

        @NonBlocking
        @GreenScheduled(identity = "nonBlocking", successive = "0s 1s 1s", duration = "1m", carbonIntensityZone = "NL")
        void nonBlockingJob() {
        }
    }
}
