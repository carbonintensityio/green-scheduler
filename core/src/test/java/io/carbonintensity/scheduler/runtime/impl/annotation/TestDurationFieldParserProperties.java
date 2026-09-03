package io.carbonintensity.scheduler.runtime.impl.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests for {@link DurationFieldParser#parseDuration}, alongside its incidental coverage as
 * literal strings ("15m", "30m", "1h") in {@link SuccessiveExpressionParser} and
 * {@link GreenScheduledAnnotationParser} tests.
 * <p>
 * This class was picked for property-based testing because {@code parseDuration} is shared by both scheduling
 * modes ({@link SuccessiveExpressionParser} and the duration field parsed via
 * {@link GreenScheduledAnnotationParser}) - an edge case here affects both - yet it had no dedicated test
 * class and no negative/zero/case-variant coverage.
 * <p>
 * The core invariant checked here: for a digit-starting simplified duration string (e.g. "15m", "1h", "2d"),
 * {@code parseDuration} must produce exactly the same {@link Duration} as parsing the equivalent full
 * ISO-8601 P/PT form directly (e.g. "PT15M", "PT1H", "P2D") - and the suffix's case ('d' vs 'D', etc.) must
 * never change the result.
 *
 * <p>
 * See {@code docs/adr/0002-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestDurationFieldParserProperties {

    // The single-letter ISO-8601 duration designators DurationFieldParser actually branches on: 'D' takes the
    // bare "P" prefix, while H/M/S all take "PT" - covering both branches of that if/else.
    private static final Arbitrary<Character> UNITS = size -> Gen.choose(new char[] { 'D', 'H', 'M', 'S' });

    // Zero included deliberately: it is the boundary where "P0D" and "PT0H"/"PT0M"/"PT0S" all collapse to the
    // same Duration.ZERO, which is exactly the kind of degenerate case example-based tests tend to skip.
    private static final Arbitrary<Long> MAGNITUDES = size -> Gen.choose(0L, 1_000_000L);

    private static final Arbitrary<Boolean> LOWERCASE_SUFFIX = size -> Gen.choose(true, false);

    @Test
    void simplifiedFormMatchesFullIsoForm() {
        Property.def("parseDuration(<n><unit>) == Duration.parse(equivalent full ISO-8601 P/PT form)")
                .forAll(MAGNITUDES, UNITS, LOWERCASE_SUFFIX)
                .suchThat((magnitude, unit, lowercase) -> {
                    String simplified = magnitude + suffix(unit, lowercase);
                    String fullIso = unit == 'D' ? "P" + magnitude + "D" : "PT" + magnitude + unit;

                    Duration actual = DurationFieldParser.parseDuration(simplified);
                    Duration expected = Duration.parse(fullIso);

                    return actual.equals(expected);
                })
                .check()
                .assertIsSatisfied();
    }

    @Test
    void suffixCaseNeverChangesTheResult() {
        Property.def("parseDuration(<n><unit>) == parseDuration(<n><UNIT>) regardless of suffix case")
                .forAll(MAGNITUDES, UNITS)
                .suchThat((magnitude, unit) -> {
                    Duration lower = DurationFieldParser.parseDuration(magnitude + suffix(unit, true));
                    Duration upper = DurationFieldParser.parseDuration(magnitude + suffix(unit, false));

                    return lower.equals(upper);
                })
                .check()
                .assertIsSatisfied();
    }

    // Deterministic complements to the properties above: the exact examples named in the ticket, so a
    // regression is caught on every run rather than only when the property generator happens to land on one.

    @Test
    void fifteenMinutesMatchesFullIsoForm() {
        assertThat(DurationFieldParser.parseDuration("15m")).isEqualTo(Duration.parse("PT15M"));
    }

    @Test
    void oneHourMatchesFullIsoForm() {
        assertThat(DurationFieldParser.parseDuration("1h")).isEqualTo(Duration.parse("PT1H"));
    }

    @Test
    void twoDaysMatchesFullIsoForm() {
        assertThat(DurationFieldParser.parseDuration("2d")).isEqualTo(Duration.parse("P2D"));
    }

    @Test
    void zeroIsTheSameDurationRegardlessOfUnit() {
        assertThat(DurationFieldParser.parseDuration("0d"))
                .isEqualTo(DurationFieldParser.parseDuration("0h"))
                .isEqualTo(DurationFieldParser.parseDuration("0m"))
                .isEqualTo(DurationFieldParser.parseDuration("0s"))
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void uppercaseDaySuffixIsEquivalentToLowercase() {
        assertThat(DurationFieldParser.parseDuration("2D")).isEqualTo(DurationFieldParser.parseDuration("2d"));
    }

    private static String suffix(char unit, boolean lowercase) {
        return lowercase ? String.valueOf(Character.toLowerCase(unit)) : String.valueOf(unit);
    }
}
