package io.carbonintensity.scheduler.runtime;

/**
 * This class is to trigger time checks within {@link SimpleScheduler},
 * used from {@link io.carbonintensity.scheduler.test.helper.MutableClock}
 * after shifting time in tests to avoid waiting for the next periodic time check cycle
 */
public class SimpleSchedulerNotifier {
    SimpleScheduler scheduler;

    public void register(SimpleScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public void check() {
        if (scheduler != null) {
            scheduler.checkTriggers();
        }
    }
}
