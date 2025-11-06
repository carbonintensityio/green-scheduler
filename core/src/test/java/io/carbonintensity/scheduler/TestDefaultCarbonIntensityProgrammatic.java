package io.carbonintensity.scheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.runtime.SchedulerConfig;
import io.carbonintensity.scheduler.runtime.SimpleScheduler;
import io.carbonintensity.scheduler.test.helper.DisabledDummyCarbonIntensityApi;

class TestDefaultCarbonIntensityProgrammatic {

    private static final Logger log = LoggerFactory.getLogger(TestDefaultCarbonIntensityProgrammatic.class);
    private SimpleScheduler scheduler;
    private AtomicInteger jobId;
    private final CarbonIntensityApi disabledApi = new DisabledDummyCarbonIntensityApi();

    @BeforeEach
    public void beforeEach() {
        jobId = new AtomicInteger(0);
        var schedulerConfig = new SchedulerConfig();
        schedulerConfig.setCarbonIntensityApi(disabledApi);
        scheduler = new SimpleScheduler(schedulerConfig);
    }

    @AfterEach
    public void afterEach() {
        scheduler.close();
    }

    @Test
    void testConcurrent() {
        CountDownLatch cdl = new CountDownLatch(3);
        scheduler.newJob("test")
                .setConcurrentExecution(ConcurrentExecution.PROCEED)
                .setDuration(Duration.ofSeconds(3))
                .setMinimumGap(Duration.ofSeconds(1))
                .setMaximumGap(Duration.ofSeconds(1))
                .setCarbonIntensityZone("NL")
                .setTask(se -> {
                    task(se, cdl, 3);
                })
                .schedule();

        Awaitility.waitAtMost(30, TimeUnit.SECONDS)
                .until(() -> cdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
    }

    @Test
    void testConcurrentSkip() {
        CountDownLatch cdl = new CountDownLatch(1);
        CountDownLatch skipCdl = new CountDownLatch(4);
        scheduler.addJobListener(new Scheduler.EventListener() {
            @Override
            public void jobExecutionSkipped(ScheduledExecution execution, String detail) {
                String id = execution.getTrigger().getId() + "-" + jobId.getAndIncrement();
                log.info("Skipping {}, scheduledAt {} because: {}", id, execution.getScheduledFireTime(), detail);
                skipCdl.countDown();
            }
        });
        scheduler.newJob("test")
                .setConcurrentExecution(ConcurrentExecution.SKIP)
                .setDuration(Duration.ofSeconds(5))
                .setMinimumGap(Duration.ofSeconds(1))
                .setMaximumGap(Duration.ofSeconds(1))
                .setCarbonIntensityZone("NL")
                .setTask(se -> {
                    task(se, cdl, 5);
                })
                .schedule();

        Awaitility.waitAtMost(30, TimeUnit.SECONDS)
                .until(() -> cdl.getCount() == 0 && skipCdl.getCount() == 0);
        Assertions.assertThat(cdl.getCount()).isZero();
        Assertions.assertThat(skipCdl.getCount()).isZero();
    }

    private void task(ScheduledExecution se, CountDownLatch cdl, long taskSeconds) {
        String id = se.getTrigger().getId() + "-" + jobId.getAndIncrement();
        log.info("Running {}, scheduledAt {}, startedAt {}", id, se.getScheduledFireTime(), se.getFireTime());
        Awaitility.await().pollDelay(Duration.ofSeconds(taskSeconds)).until(() -> true);
        cdl.countDown();
        log.info("Finished {}", id);
    }
}
