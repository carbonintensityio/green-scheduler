package io.carbonintensity.scheduler.quarkus.runtime;

import java.lang.annotation.Annotation;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import org.jboss.logging.Logger;

import io.carbonintensity.scheduler.ConcurrentExecution;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.SkipPredicate;
import io.carbonintensity.scheduler.quarkus.common.runtime.ScheduledMethod;
import io.carbonintensity.scheduler.quarkus.common.runtime.SchedulerContext;
import io.carbonintensity.scheduler.quarkus.common.runtime.util.SchedulerUtils;
import io.carbonintensity.scheduler.runtime.ImmutableScheduledMethod;
import io.carbonintensity.scheduler.runtime.ScheduledInvoker;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.quarkus.runtime.Startup;

@Singleton
@Startup
public class QuarkusScheduler implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(QuarkusScheduler.class);

    SimpleScheduler greenScheduler;

    public QuarkusScheduler(SchedulerContext context, SimpleScheduler greenScheduler) {
        this.greenScheduler = greenScheduler;
        if (context.getScheduledMethods().isEmpty()) {
            LOG.info("No scheduled business methods found");
            return;
        }

        // Create triggers and invokers for @GreenScheduled methods
        for (ScheduledMethod method : context.getScheduledMethods()) {
            ScheduledInvoker invoker = context.createInvoker(method.getInvokerClassName());
            var schedules = method.getSchedules().stream().map(this::lookupConfiguration).collect(Collectors.toList());
            greenScheduler.scheduleMethod(new ImmutableScheduledMethod(invoker, method.getDeclaringClassName(),
                    method.getMethodName(), schedules));
        }
    }

    @PreDestroy
    @Override
    public void close() {
        LOG.info("Closing Green Scheduler");
        greenScheduler.close();
    }

    GreenScheduled lookupConfiguration(GreenScheduled scheduled) {
        return new GreenScheduled() {

            @Override
            public Class<? extends Annotation> annotationType() {
                return scheduled.annotationType();
            }

            @Override
            public String identity() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.identity());
            }

            @Override
            public String fixedWindow() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.fixedWindow());
            }

            @Override
            public String timeZone() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.timeZone());
            }

            @Override
            public String dayOfMonth() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.dayOfMonth());
            }

            @Override
            public String dayOfWeek() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.dayOfWeek());
            }

            @Override
            public String successive() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.successive());
            }

            @Override
            public String cron() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.cron());
            }

            @Override
            public String duration() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.duration());
            }

            @Override
            public String carbonIntensityZone() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.carbonIntensityZone());
            }

            @Override
            public ConcurrentExecution concurrentExecution() {
                return scheduled.concurrentExecution();
            }

            @Override
            public Class<? extends SkipPredicate> skipExecutionIf() {
                return scheduled.skipExecutionIf();
            }

            @Override
            public String overdueGracePeriod() {
                return SchedulerUtils.lookUpPropertyValue(scheduled.overdueGracePeriod());
            }
        };
    }

}
