package io.carbonintensity.executionplanner.planner.fixedwindow;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;

/**
 * Data class containing the constraints that are used by {@link FixedWindowPlanner} to plan the best window.
 */
public class DefaultFixedWindowPlanningConstraints extends FixedWindowPlanningConstraints {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFixedWindowPlanningConstraints.class);

    private final String identity;
    private final Duration duration;
    private final String carbonIntensityZone;
    private final ZonedDateTime startTime;
    private final ZonedDateTime endTime;
    private final Cron fallbackCronExpression;
    private final ZoneId timeZoneId;
    private final Cron cronExpression;

    public DefaultFixedWindowPlanningConstraints(String identity,
            Duration duration,
            String carbonIntensityZone,
            ZonedDateTime startTime,
            ZonedDateTime endTime,
            Cron fallbackCronExpression,
            ZoneId timeZoneId,
            Cron cronExpression) {
        this.identity = identity;
        this.duration = duration;
        this.carbonIntensityZone = carbonIntensityZone;
        this.cronExpression = cronExpression;
        int delayDays = 0;
        if (!checkStartTime(startTime, cronExpression)) {
            delayDays = calculateDelayedDays(startTime, cronExpression);
        }
        this.startTime = startTime.plusDays(delayDays);
        this.endTime = endTime.plusDays(delayDays);
        this.fallbackCronExpression = fallbackCronExpression;
        this.timeZoneId = timeZoneId;
    }

    @Override
    public String getIdentity() {
        return identity;
    }

    @Override
    public Duration getDuration() {
        return duration;
    }

    @Override
    public String getCarbonIntensityZone() {
        return carbonIntensityZone;
    }

    @Override
    public ZonedDateTime getStart() {
        return startTime;
    }

    @Override
    public ZonedDateTime getEnd() {
        return endTime;
    }

    @Override
    public ZoneId getTimeZoneId() {
        return timeZoneId;
    }

    @Override
    public Cron getCronExpression() {
        return cronExpression;
    }

    @Override
    public Cron getFallbackCronExpression() {
        return this.fallbackCronExpression;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DefaultFixedWindowPlanningConstraints.Builder from(FixedWindowPlanningConstraints constraints) {
        return new DefaultFixedWindowPlanningConstraints.Builder()
                .withIdentity(constraints.getIdentity())
                .withDuration(constraints.getDuration())
                .withCarbonIntensityZone(constraints.getCarbonIntensityZone())
                .withCronExpression(constraints.getCronExpression())
                .withStartAndEnd(constraints.getStart(), constraints.getEnd())
                .withFallbackCronExpression(constraints.getFallbackCronExpression())
                .withTimeZoneId(constraints.getTimeZoneId());
    }

    private static boolean checkStartTime(ZonedDateTime startTime, Cron cron) {
        startTime = (startTime.getNano() > 0) ? startTime.truncatedTo(ChronoUnit.SECONDS) : startTime;
        return ExecutionTime.forCron(cron).isMatch(startTime);
    }

    private static int calculateDelayedDays(ZonedDateTime startTime, Cron cron) {
        startTime = (startTime.getNano() > 0) ? startTime.truncatedTo(ChronoUnit.SECONDS) : startTime;
        int delayDays = 1;
        while (delayDays < 35) {
            if (ExecutionTime.forCron(cron).isMatch(startTime.plusDays(delayDays))) {
                return delayDays;
            } else {
                delayDays++;
            }
        }
        logger.error(
                "Failed to get a new date that satisfies the constraints of day-of-month or day-of-week within a month. "
                        +
                        "fallback on daily");
        return 0;
    }

    public static final class Builder {
        private String identity;
        private Duration duration;
        private String carbonIntensityZone;
        private ZonedDateTime startTime;
        private ZonedDateTime endTime;
        private Cron fallbackCronExpression;
        private ZoneId timeZoneId;
        private Cron cronExpression;

        private Builder() {
        }

        public Builder withIdentity(String identity) {
            this.identity = identity;
            return this;
        }

        public Builder withDuration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder withCronExpression(Cron cronExpression) {
            this.cronExpression = cronExpression;
            return this;
        }

        public Builder withCarbonIntensityZone(String carbonIntensityZone) {
            this.carbonIntensityZone = carbonIntensityZone;
            return this;
        }

        public Builder withStartAndEnd(ZonedDateTime startTime, ZonedDateTime endTime) {
            int delayDays = 0;
            if (!checkStartTime(startTime, cronExpression)) {
                delayDays = calculateDelayedDays(startTime, cronExpression);
            }
            this.startTime = startTime.plusDays(delayDays);
            this.endTime = endTime.plusDays(delayDays);
            return this;
        }

        public Builder withFallbackCronExpression(Cron fallbackCronExpression) {
            this.fallbackCronExpression = fallbackCronExpression;
            return this;
        }

        public Builder withTimeZoneId(ZoneId timeZoneId) {
            this.timeZoneId = timeZoneId;
            return this;
        }

        public DefaultFixedWindowPlanningConstraints build() {
            return new DefaultFixedWindowPlanningConstraints(identity, duration, carbonIntensityZone, startTime, endTime,
                    fallbackCronExpression, timeZoneId, cronExpression);
        }

    }
}
