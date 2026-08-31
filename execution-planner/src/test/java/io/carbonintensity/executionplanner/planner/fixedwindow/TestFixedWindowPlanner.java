package io.carbonintensity.executionplanner.planner.fixedwindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensityDataFetcher;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityJsonParser;
import io.carbonintensity.executionplanner.spi.CarbonIntensityPlanner;
import io.carbonintensity.executionplanner.spi.ConcurrencySlotTracker;

@ExtendWith(MockitoExtension.class)
class TestFixedWindowPlanner {

    FixedWindowPlanner defaultCarbonIntensityScheduler;
    CarbonIntensityDataFetcher carbonIntensityDataFetcher;

    @BeforeEach
    public void setup() {
        carbonIntensityDataFetcher = mock(CarbonIntensityDataFetcher.class);
        defaultCarbonIntensityScheduler = new FixedWindowPlanner(carbonIntensityDataFetcher);
    }

    private static FixedWindowPlanningConstraints constraintsFor(String identity, ZonedDateTime start, ZonedDateTime end) {
        CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        Cron cron = cronParser.parse(String.format("%d %d %d * * ?", start.getSecond(), start.getMinute(), start.getHour()));
        Cron cronFallback = cronParser.parse("0 0 12 * * ?");

        return DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity(identity)
                .withDuration(Duration.ofMinutes(30))
                .withCarbonIntensityZone("NL")
                .withCronExpression(cron)
                .withStartAndEnd(start, end)
                .withFallbackCronExpression(cronFallback)
                .withTimeZoneId(ZoneId.of("UTC"))
                .build();
    }

    @Test
    void whenTwoJobsCompeteForTheSameSlot_thenTheSecondJobIsSpreadToTheNextBestSlot() {
        CarbonIntensityDataFetcher sharedFetcher = mock(CarbonIntensityDataFetcher.class);
        CarbonIntensityJsonParser parser = new CarbonIntensityJsonParser();
        var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));
        when(sharedFetcher.fetchCarbonIntensity(any())).thenReturn(carbonIntensity);

        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();
        CarbonIntensityPlanner<FixedWindowPlanningConstraints> plannerA = new FixedWindowPlanner(sharedFetcher, tracker, 1);
        CarbonIntensityPlanner<FixedWindowPlanningConstraints> plannerB = new FixedWindowPlanner(sharedFetcher, tracker, 1);

        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ws.plusHours(6);
        var constraintsA = constraintsFor("job-a", ws, we);
        var constraintsB = constraintsFor("job-b", ws, we);

        ZonedDateTime timeA = plannerA.getNextExecutionTime(constraintsA);
        ZonedDateTime timeB = plannerB.getNextExecutionTime(constraintsB);

        assertThat(timeA).isNotNull();
        assertThat(timeB).isNotNull();
        assertThat(timeB).isNotEqualTo(timeA);
    }

    @Test
    void whenNoSlotSatisfiesTheLimit_thenTheWindowStillWinsAndJobRunsAnyway() {
        CarbonIntensityDataFetcher sharedFetcher = mock(CarbonIntensityDataFetcher.class);
        CarbonIntensityJsonParser parser = new CarbonIntensityJsonParser();
        var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));
        when(sharedFetcher.fetchCarbonIntensity(any())).thenReturn(carbonIntensity);

        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();
        // window only wide enough for a single 30-minute slot, so no alternative slot can ever exist
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ws.plusMinutes(30);
        int maxConcurrentPerSlot = 1;

        CarbonIntensityPlanner<FixedWindowPlanningConstraints> plannerA = new FixedWindowPlanner(sharedFetcher, tracker,
                maxConcurrentPerSlot);
        CarbonIntensityPlanner<FixedWindowPlanningConstraints> plannerB = new FixedWindowPlanner(sharedFetcher, tracker,
                maxConcurrentPerSlot);

        ZonedDateTime timeA = plannerA.getNextExecutionTime(constraintsFor("job-a", ws, we));
        ZonedDateTime timeB = plannerB.getNextExecutionTime(constraintsFor("job-b", ws, we));

        assertThat(timeA).isNotNull();
        assertThat(timeB).isNotNull();
        // no room to spread within this window: both jobs still run, at the same greenest slot
        assertThat(timeB).isEqualTo(timeA);
    }

    @Test
    void shouldStandardSchedule() {
        final var parser = new CarbonIntensityJsonParser();
        final var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));

        when(carbonIntensityDataFetcher.fetchCarbonIntensity(any()))
                .thenReturn(carbonIntensity);

        ZonedDateTime now = ZonedDateTime.now();

        int seconds = now.getSecond();
        int minutes = now.getMinute();
        int hours = now.getHour();
        String cronExpression = String.format("%d %d %d * * ?", seconds, minutes, hours);
        CronParser cronparser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        Cron cron = cronparser.parse(cronExpression);

        String cronExpressionFallback = "0 0 12 * * ?";
        Cron cronFallback = cronparser.parse(cronExpressionFallback);

        final var constraints = DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity("foo")
                .withDuration(Duration.ofMinutes(60))
                .withCarbonIntensityZone("NL")
                .withCronExpression(cron)
                .withStartAndEnd(now, now.plusHours(6))
                .withFallbackCronExpression(cronFallback)
                .withTimeZoneId(ZoneId.of("UTC"))
                .build();

        ZonedDateTime nextExecutionTime = defaultCarbonIntensityScheduler.getNextExecutionTime(constraints);
        assertThat(nextExecutionTime).isNotNull();
        assertThat(nextExecutionTime).isAfter(now.minusMinutes(1));
        assertThat(nextExecutionTime).isBefore(now.plusDays(1));
    }

    @Test
    void shouldScheduleOnFirstMonday() {
        final var parser = new CarbonIntensityJsonParser();
        final var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));

        when(carbonIntensityDataFetcher.fetchCarbonIntensity(any()))
                .thenReturn(carbonIntensity);

        ZonedDateTime now = ZonedDateTime.now();

        int seconds = now.getSecond();
        int minutes = now.getMinute();
        int hours = now.getHour();
        String cronExpression = String.format("%d %d %d ? * MON", seconds, minutes, hours);
        CronParser cronparser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        Cron cron = cronparser.parse(cronExpression);

        String cronExpressionFallback = "0 0 12 * * ?";
        Cron cronFallback = cronparser.parse(cronExpressionFallback);

        final var constraints = DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity("foo")
                .withDuration(Duration.ofMinutes(60))
                .withCarbonIntensityZone("NL")
                .withCronExpression(cron)
                .withStartAndEnd(now, now.plusHours(6))
                .withFallbackCronExpression(cronFallback)
                .withTimeZoneId(ZoneId.of("UTC"))
                .build();

        ZonedDateTime nextExecutionTime = defaultCarbonIntensityScheduler.getNextExecutionTime(constraints);
        assertThat(nextExecutionTime).isNotNull();
        assertThat(nextExecutionTime.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(nextExecutionTime).isAfter(now.minusMinutes(1));
        assertThat(nextExecutionTime).isBefore(now.plusDays(7));
    }

    @Test
    void shouldScheduleOnFirstDayOfMonth() {
        final var parser = new CarbonIntensityJsonParser();
        final var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));

        when(carbonIntensityDataFetcher.fetchCarbonIntensity(any()))
                .thenReturn(carbonIntensity);

        ZonedDateTime now = ZonedDateTime.now();

        int seconds = now.getSecond();
        int minutes = now.getMinute();
        int hours = now.getHour();
        String cronExpression = String.format("%d %d %d 1 * ?", seconds, minutes, hours);
        CronParser cronparser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        Cron cron = cronparser.parse(cronExpression);

        String cronExpressionFallback = "0 0 12 * * ?";
        Cron cronFallback = cronparser.parse(cronExpressionFallback);

        final var constraints = DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity("foo")
                .withDuration(Duration.ofMinutes(60))
                .withCarbonIntensityZone("NL")
                .withCronExpression(cron)
                .withStartAndEnd(now, now.plusHours(6))
                .withFallbackCronExpression(cronFallback)
                .withTimeZoneId(ZoneId.of("UTC"))
                .build();

        ZonedDateTime nextExecutionTime = defaultCarbonIntensityScheduler.getNextExecutionTime(constraints);
        assertThat(nextExecutionTime).isNotNull();
        assertThat(nextExecutionTime.getDayOfMonth()).isEqualTo(1);
        assertThat(nextExecutionTime).isAfter(now.minusMinutes(1));
        assertThat(nextExecutionTime).isBefore(now.plusDays(31));
    }

    @Test
    void shouldNotScheduleOnSunday() {
        final var parser = new CarbonIntensityJsonParser();
        final var carbonIntensity = parser.parse(ClassLoader.getSystemResourceAsStream("day-ahead-20240824-Z.json"));

        when(carbonIntensityDataFetcher.fetchCarbonIntensity(any()))
                .thenReturn(carbonIntensity);

        ZonedDateTime date = ZonedDateTime.parse("2025-04-27T14:30+02:00");

        int seconds = date.getSecond();
        int minutes = date.getMinute();
        int hours = date.getHour();
        String cronExpression = String.format("%d %d %d ? * MON-SAT", seconds, minutes, hours);
        CronParser cronparser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        Cron cron = cronparser.parse(cronExpression);

        String cronExpressionFallback = "0 0 12 * * ?";
        Cron cronFallback = cronparser.parse(cronExpressionFallback);

        final var constraints = DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity("foo")
                .withDuration(Duration.ofMinutes(60))
                .withCarbonIntensityZone("NL")
                .withCronExpression(cron)
                .withStartAndEnd(date, date.plusHours(6))
                .withFallbackCronExpression(cronFallback)
                .withTimeZoneId(ZoneId.of("UTC"))
                .build();

        ZonedDateTime nextExecutionTime = defaultCarbonIntensityScheduler.getNextExecutionTime(constraints);
        assertThat(nextExecutionTime).isNotNull();
        assertThat(nextExecutionTime.getDayOfWeek()).isNotEqualTo(DayOfWeek.SUNDAY);
        assertThat(nextExecutionTime).isAfter(date.minusMinutes(1));
        assertThat(nextExecutionTime).isBefore(date.plusDays(7));
    }
}
