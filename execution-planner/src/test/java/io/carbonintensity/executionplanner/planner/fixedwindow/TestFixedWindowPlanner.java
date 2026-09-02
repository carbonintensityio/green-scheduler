package io.carbonintensity.executionplanner.planner.fixedwindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
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
        return constraintsFor(identity, start, end, Duration.ofMinutes(30));
    }

    private static FixedWindowPlanningConstraints constraintsFor(String identity, ZonedDateTime start, ZonedDateTime end,
            Duration duration) {
        CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
        Cron cron = cronParser.parse(String.format("%d %d %d * * ?", start.getSecond(), start.getMinute(), start.getHour()));
        Cron cronFallback = cronParser.parse("0 0 12 * * ?");

        return DefaultFixedWindowPlanningConstraints.builder()
                .withIdentity(identity)
                .withDuration(duration)
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

    /**
     * When several candidate slots tie on carbon intensity, {@code SingleJobStrategy#rankedTimeslots}
     * breaks the tie by chronological order (see {@code TestSingleJobStrategy}). This pins that the same
     * deterministic order is what spreading walks through here: competing jobs land on the tied slots in
     * chronological order, not in some incidental order.
     */
    @Test
    void whenThreeJobsCompeteForTiedSlots_thenTheyAreSpreadInChronologicalOrder() {
        CarbonIntensityDataFetcher sharedFetcher = mock(CarbonIntensityDataFetcher.class);

        // hourly data: 01:00, 02:00 and 03:00 all tie at 50, so with a 1-hour job duration those three
        // timeslots are the tied candidates; 00:00 (100) and 04:00 (200) are strictly worse.
        CarbonIntensity carbonIntensity = new CarbonIntensity();
        carbonIntensity.setZone("NL");
        carbonIntensity.setResolution(Duration.ofHours(1));
        carbonIntensity.setStart(Instant.parse("2024-01-01T00:00:00Z"));
        carbonIntensity.setEnd(Instant.parse("2024-01-01T05:00:00Z"));
        carbonIntensity.setData(List.of(
                new BigDecimal("100"),
                new BigDecimal("50"),
                new BigDecimal("50"),
                new BigDecimal("50"),
                new BigDecimal("200")));
        when(sharedFetcher.fetchCarbonIntensity(any())).thenReturn(carbonIntensity);

        ConcurrencySlotTracker tracker = new ConcurrencySlotTracker();
        CarbonIntensityPlanner<FixedWindowPlanningConstraints> plannerA = new FixedWindowPlanner(sharedFetcher, tracker, 1);
        CarbonIntensityPlanner<FixedWindowPlanningConstraints> plannerB = new FixedWindowPlanner(sharedFetcher, tracker, 1);
        CarbonIntensityPlanner<FixedWindowPlanningConstraints> plannerC = new FixedWindowPlanner(sharedFetcher, tracker, 1);

        ZonedDateTime ws = ZonedDateTime.parse("2024-01-01T00:00:00Z");
        ZonedDateTime we = ws.plusHours(4);
        Duration duration = Duration.ofHours(1);

        ZonedDateTime timeA = plannerA.getNextExecutionTime(constraintsFor("job-a", ws, we, duration));
        ZonedDateTime timeB = plannerB.getNextExecutionTime(constraintsFor("job-b", ws, we, duration));
        ZonedDateTime timeC = plannerC.getNextExecutionTime(constraintsFor("job-c", ws, we, duration));

        // the three tied (CI 50) slots are claimed in chronological order: 01:00, then 02:00, then 03:00
        assertThat(timeA).isEqualTo(ws.plusHours(1));
        assertThat(timeB).isEqualTo(ws.plusHours(2));
        assertThat(timeC).isEqualTo(ws.plusHours(3));
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
