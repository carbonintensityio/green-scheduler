package io.carbonintensity.scheduler.runtime.impl.annotation;

import static io.carbonintensity.scheduler.runtime.impl.annotation.DurationFieldParser.parseDuration;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import io.carbonintensity.executionplanner.planner.fixedwindow.DefaultFixedWindowPlanningConstraints;
import io.carbonintensity.executionplanner.planner.successive.DefaultSuccessivePlanningConstraints;
import io.carbonintensity.executionplanner.spi.PlanningConstraints;
import io.carbonintensity.scheduler.GreenScheduled;

/**
 * Parses {@link GreenScheduled} annotations and generates corresponding {@link PlanningConstraints}.
 *
 * This utility class processes the scheduling constraints defined in the {@link GreenScheduled} annotation
 * and converts them into either {@link DefaultFixedWindowPlanningConstraints} or
 * {@link DefaultSuccessivePlanningConstraints}, depending on the annotation's configuration.
 *
 */
public class GreenScheduledAnnotationParser {

    private GreenScheduledAnnotationParser() {
    }

    public static Duration parseOverdueGracePeriod(GreenScheduled scheduled, Duration defaultGracePeriod) {
        String overdueGracePeriodString = scheduled.overdueGracePeriod();
        if (overdueGracePeriodString == null || overdueGracePeriodString.isBlank()) {
            return defaultGracePeriod;
        }
        try {
            return Duration.parse(overdueGracePeriodString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ISO 8601 duration format: " + overdueGracePeriodString, e);
        }
    }

    public static Cron parseCronExpression(ZonedDateTime startTime, String dayOfMonth, String dayOfWeek) {
        CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ);
        CronParser cronParser = new CronParser(cronDefinition);
        int hour = startTime.getHour();
        int minute = startTime.getMinute();
        int second = startTime.getSecond();

        if (dayOfMonth != null && !dayOfMonth.isBlank()) {
            try {
                String cronString = String.format("%d %d %d %s * ?", second, minute, hour, dayOfMonth);
                return cronParser.parse(cronString);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid CRON format: " + dayOfMonth, e);
            }
        }
        if (dayOfWeek != null && !dayOfWeek.isBlank()) {
            try {
                String cronString = String.format("%d %d %d ? * %s", second, minute, hour, dayOfWeek);
                return cronParser.parse(cronString);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid CRON format: " + dayOfWeek, e);
            }
        }
        try {
            String cronString = String.format("%d %d %d * * ?", second, minute, hour);
            return cronParser.parse(cronString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CRON format", e);
        }
    }

    public static PlanningConstraints createConstraints(String identity, GreenScheduled annotation, Clock clock) {
        List<String> validationErrors = GreenScheduledAnnotationValidation.validateAndReturnValidationErrors(annotation);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Found " + validationErrors.size() + " validation error" + (validationErrors.size() > 1 ? "2" : "")
                            + " while creating GreenScheduled constraints for "
                            + identity + ": \n" + String.join("\n", validationErrors));
        }

        ZoneId timeZoneId = Optional.ofNullable(annotation.timeZone())
                .filter(timeZone -> !timeZone.isEmpty())
                .map(ZoneId::of)
                .orElse(ZoneId.systemDefault());

        final var optionalFixedWindow = FixedWindowExpressionParser.parse(annotation.fixedWindow(), clock, timeZoneId);
        if (optionalFixedWindow.isPresent()) {
            final var fixedWindow = optionalFixedWindow.get();

            Cron fallBackCronExpression = getFallBackCronExpression(annotation, fixedWindow);

            return DefaultFixedWindowPlanningConstraints.builder()
                    .withIdentity(identity)
                    .withDuration(parseDuration(annotation.duration()))
                    .withCronExpression(
                            parseCronExpression(fixedWindow.getStartTime(), annotation.dayOfMonth(), annotation.dayOfWeek()))
                    .withStartAndEnd(fixedWindow.getStartTime(), fixedWindow.getEndTime())
                    .withZone(annotation.zone())
                    .withTimeZoneId(timeZoneId)
                    .withFallbackCronExpression(fallBackCronExpression)
                    .build();
        }

        final var optionalSuccessive = SuccessiveExpressionParser.parse(annotation.successive());
        if (optionalSuccessive.isPresent()) {
            final var successive = optionalSuccessive.get();
            return DefaultSuccessivePlanningConstraints.builder()
                    .withIdentity(identity)
                    .withInitialStartTime(ZonedDateTime.now(clock))
                    .withInitialMaximumDelay(successive.getInitialMaximumDelay())
                    .withMinimumGap(successive.getMinimumGap())
                    .withMaximumGap(successive.getMaximumGap())
                    .withDuration(parseDuration(annotation.duration()))
                    .withZone(annotation.zone())
                    .build();
        }

        throw new IllegalArgumentException("Not yet implemented");
    }

    private static Cron getFallBackCronExpression(GreenScheduled annotation, FixedWindowConstraints fixedWindow) {
        CronDefinition definition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ);
        String cron = annotation.cron();
        if (cron == null || cron.isEmpty()) {
            cron = calculateFallBackCronExpression(fixedWindow);
        }
        return new CronParser(definition).parse(cron);
    }

    private static String calculateFallBackCronExpression(FixedWindowConstraints fixedWindow) {
        String cron;
        int dailyWindowInSeconds = fixedWindow.getEndTime().toLocalTime().toSecondOfDay()
                - fixedWindow.getStartTime().toLocalTime().toSecondOfDay();
        LocalTime averageTime = fixedWindow.getStartTime().toLocalTime().plusSeconds(dailyWindowInSeconds / 2);
        cron = String.format("0 %s %s * * ?", averageTime.getMinute(), averageTime.getHour());
        return cron;
    }
}
