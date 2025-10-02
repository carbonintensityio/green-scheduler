package io.carbonintensity.scheduler.spring;

import io.carbonintensity.scheduler.ConcurrentExecution;
import io.carbonintensity.scheduler.GreenScheduled;

public class TestScheduledJob {

    @GreenScheduled(identity = "testJob", duration = "PT5S", successive = "0H PT1H PT2H", concurrentExecution = ConcurrentExecution.PROCEED, overdueGracePeriod = "PT1H", carbonIntensityZone = "nl")
    public void run() {
        System.out.println("running");
    }

    public void log() {
        System.out.println("logging");
    }
}
