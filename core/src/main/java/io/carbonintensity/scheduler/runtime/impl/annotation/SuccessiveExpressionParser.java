package io.carbonintensity.scheduler.runtime.impl.annotation;

import java.util.Optional;

/**
 * Util class to parse successiveConstraints.
 */
public class SuccessiveExpressionParser {

    private SuccessiveExpressionParser() {
    }

    public static Optional<SuccessiveConstraints> parse(String expression) {
        if (expression == null || expression.isEmpty()) {
            return Optional.empty();
        }
        final var parts = expression.split(" ");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Fixed Window Expression should contain an initial interval and a start and end time separated by a space");
        }
        return Optional.of(new SuccessiveConstraints(
                DurationFieldParser.parseDuration(parts[0]),
                DurationFieldParser.parseDuration(parts[1]),
                DurationFieldParser.parseDuration(parts[2])));
    }
}
