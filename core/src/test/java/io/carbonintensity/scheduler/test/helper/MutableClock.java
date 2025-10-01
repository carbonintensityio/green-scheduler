package io.carbonintensity.scheduler.test.helper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import io.carbonintensity.scheduler.runtime.SimpleSchedulerNotifier;

public class MutableClock extends Clock {
    private final Clock baseClock;
    private Duration offset = Duration.ZERO;
    private SimpleSchedulerNotifier notifier;

    private MutableClock(Clock baseClock, SimpleSchedulerNotifier notifier) {
        this.baseClock = baseClock;
        this.notifier = notifier;
    }

    public MutableClock(Clock baseClock) {
        this(baseClock, new SimpleSchedulerNotifier());
    }

    public void shift(Duration duration) {
        this.offset = this.offset.plus(duration);
        this.notifier.check();
    }

    public SimpleSchedulerNotifier getNotifier() {
        return notifier;
    }

    @Override
    public ZoneId getZone() {
        return baseClock.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock mutableClock = new MutableClock(baseClock.withZone(zone), notifier);
        mutableClock.shift(this.offset);
        return mutableClock;
    }

    @Override
    public Instant instant() {
        return baseClock.instant().plus(offset);
    }
}
