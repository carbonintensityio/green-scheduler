package io.carbonintensity.scheduler.runtime.impl.annotation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * Util class to parse fixedWindowConstraints.
 */
public class FixedWindowExpressionParser {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm")
            .withResolverStyle(ResolverStyle.LENIENT); // Supports 9:30 and 09:30

    private FixedWindowExpressionParser() {
    }

    public static Optional<FixedWindowConstraints> parse(String expression, Clock clock, ZoneId timeZoneId) {
        if (expression == null || expression.isEmpty()) {
            return Optional.empty();
        }
        final var parts = expression.split(" ");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid fixedWindow format. Expected format: '<start time> <end time>' (e.g., '9:30 11:45').");
        } else {
            try {
                LocalTime startTime = parseTime(parts[0]);
                LocalTime endTime = parseTime(parts[1]);

                ZonedDateTime zonedStartTime = getZonedStartDateTimeForNextExecutionWindow(clock, timeZoneId, startTime,
                        endTime);
                ZonedDateTime zonedEndTime = getZonedEndDateTimeForNextExecutionWindow(clock, timeZoneId, startTime, endTime);

                return Optional.of(new FixedWindowConstraints(zonedStartTime, zonedEndTime));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        String.format("Invalid time format: '%s' in fixedWindow. Expected 'HH:mm HH:mm' (e.g., '9:30 11:45').",
                                expression),
                        e);
            }
        }
    }

    private static LocalTime parseTime(String time) {
        return LocalTime.parse(time, TIME_FORMATTER);
    }

    public static ZonedDateTime getZonedStartDateTimeForNextExecutionWindow(Clock clock, ZoneId timeZoneId, LocalTime startTime,
            LocalTime endTime) {
        Clock clockForTimeZoneId = clock.withZone(timeZoneId);
        LocalDate localDateForStartTime = LocalDate.now(clockForTimeZoneId);
        boolean nowIsNextDayBeforeEndWindow = isOvernightWindow(startTime, endTime)
                && isWithinLastNightWindow(clockForTimeZoneId, startTime, endTime);
        if (nowIsNextDayBeforeEndWindow) {
            localDateForStartTime = localDateForStartTime.minusDays(1);
        }
        return ZonedDateTime.of(localDateForStartTime, startTime, timeZoneId);
    }

    public static ZonedDateTime getZonedEndDateTimeForNextExecutionWindow(Clock clock, ZoneId timeZoneId, LocalTime startTime,
            LocalTime endTime) {
        Clock clockForTimeZoneId = clock.withZone(timeZoneId);
        LocalDate localDateForEndTime = LocalDate.now(clockForTimeZoneId);
        boolean endIsNextDay = isOvernightWindow(startTime, endTime)
                && !isWithinLastNightWindow(clockForTimeZoneId, startTime, endTime);
        if (endIsNextDay) {
            localDateForEndTime = localDateForEndTime.plusDays(1);
        }
        return ZonedDateTime.of(localDateForEndTime, endTime, timeZoneId);
    }

    private static boolean isWithinLastNightWindow(Clock clockForTimeZoneId, LocalTime startTime, LocalTime endTime) {
        return LocalTime.now(clockForTimeZoneId).isBefore(endTime) || LocalTime.now(clockForTimeZoneId).isAfter(startTime);
    }

    private static boolean isOvernightWindow(LocalTime startTime, LocalTime endTime) {
        return endTime.isBefore(startTime);
    }
}
