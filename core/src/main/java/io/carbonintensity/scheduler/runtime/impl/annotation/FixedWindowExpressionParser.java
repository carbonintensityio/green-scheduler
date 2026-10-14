package io.carbonintensity.scheduler.runtime.impl.annotation;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.zone.ZoneOffsetTransition;
import java.util.Optional;

/**
 * Util class to parse fixedWindowConstraints.
 */
public class FixedWindowExpressionParser {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("H:mm")
            .withResolverStyle(ResolverStyle.LENIENT); // Supports 9:30 and 09:30

    private FixedWindowExpressionParser() {
    }

    public static Optional<FixedWindowConstraints> parse(String expression, Clock clock, ZoneId timeZoneId,
            Duration overdueGracePeriod) {
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
                        endTime, overdueGracePeriod);
                ZonedDateTime zonedEndTime = getZonedEndDateTimeForNextExecutionWindow(clock, timeZoneId, startTime, endTime,
                        overdueGracePeriod);

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
            LocalTime endTime, Duration overdueGracePeriod) {
        Clock clockForTimeZoneId = clock.withZone(timeZoneId);
        LocalDate localDateForStartTime = LocalDate.now(clockForTimeZoneId);
        boolean nowIsNextDayBeforeEndWindow = isOvernightWindow(startTime, endTime)
                && isWithinLastNightWindow(clockForTimeZoneId, startTime, endTime);
        if (nowIsNextDayBeforeEndWindow) {
            localDateForStartTime = localDateForStartTime.minusDays(1);
        } else if (todaysWindowHasAlreadyEnded(clockForTimeZoneId, startTime, endTime, overdueGracePeriod)) {
            localDateForStartTime = localDateForStartTime.plusDays(1);
        }
        return resolveWindowStart(localDateForStartTime, startTime, timeZoneId);
    }

    /**
     * A same-day window whose end time (plus its grace period - a job invoked just after the window closed, but
     * still inside the grace period, is still using today's window) is already behind "now" needs the next
     * occurrence to be tomorrow's, not today's already-closed one - only relevant for a non-overnight window,
     * since an overnight one is already handled by {@link #isWithinLastNightWindow}.
     */
    private static boolean todaysWindowHasAlreadyEnded(Clock clockForTimeZoneId, LocalTime startTime, LocalTime endTime,
            Duration overdueGracePeriod) {
        if (isOvernightWindow(startTime, endTime)) {
            return false;
        }
        ZonedDateTime todaysEndPlusGrace = ZonedDateTime.of(LocalDate.now(clockForTimeZoneId), endTime,
                clockForTimeZoneId.getZone()).plus(overdueGracePeriod);
        return !ZonedDateTime.now(clockForTimeZoneId).isBefore(todaysEndPlusGrace);
    }

    /**
     * Resolves a window's start local date-time to a {@link ZonedDateTime}, guarding against the spring-forward
     * ("gap") DST case (CIIO-329): if {@code time} falls inside a nonexistent local time range (e.g. 02:29 on a
     * day where the clock jumps from 02:00 to 03:00), the JDK's default resolver shifts it forward by the full
     * length of the gap (02:29 -> 03:29). That shift can push the resolved start past an end time that itself
     * lands just after the gap and is left untouched (e.g. 03:04), inverting the window. A window's start only
     * needs to begin no earlier than requested, so instead of shifting by the gap length, it is clamped to the
     * first valid instant at/after the gap - the earliest moment "at or after" the nominal start that actually
     * exists on the local clock.
     */
    private static ZonedDateTime resolveWindowStart(LocalDate date, LocalTime time, ZoneId zoneId) {
        LocalDateTime localDateTime = LocalDateTime.of(date, time);
        ZoneOffsetTransition transition = zoneId.getRules().getTransition(localDateTime);
        if (transition != null && transition.isGap()) {
            return ZonedDateTime.of(transition.getDateTimeAfter(), zoneId);
        }
        return ZonedDateTime.of(localDateTime, zoneId);
    }

    public static ZonedDateTime getZonedEndDateTimeForNextExecutionWindow(Clock clock, ZoneId timeZoneId, LocalTime startTime,
            LocalTime endTime, Duration overdueGracePeriod) {
        Clock clockForTimeZoneId = clock.withZone(timeZoneId);
        LocalDate localDateForEndTime = LocalDate.now(clockForTimeZoneId);
        boolean endIsNextDay = isOvernightWindow(startTime, endTime)
                && !isWithinLastNightWindow(clockForTimeZoneId, startTime, endTime);
        if (endIsNextDay || todaysWindowHasAlreadyEnded(clockForTimeZoneId, startTime, endTime, overdueGracePeriod)) {
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
