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

@ExtendWith(MockitoExtension.class)
class TestFixedWindowPlanner {

    FixedWindowPlanner defaultCarbonIntensityScheduler;
    CarbonIntensityDataFetcher carbonIntensityDataFetcher;

    @BeforeEach
    public void setup() {
        carbonIntensityDataFetcher = mock(CarbonIntensityDataFetcher.class);
        defaultCarbonIntensityScheduler = new FixedWindowPlanner(carbonIntensityDataFetcher);
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
