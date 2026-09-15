package io.carbonintensity.scheduler.spring.observability;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.carbonintensity.scheduler.GreenScheduled;
import io.vavr.test.Arbitrary;
import io.vavr.test.Property;

/**
 * Property-based test for {@link GreenSchedulerMetricsBinder#strategyOf}, alongside the example-based coverage in
 * {@link GreenSchedulerMetricsBinderTests}.
 * <p>
 * The invariant is precedence, not just individual mappings: {@code fixedWindow} wins whenever it is set,
 * regardless of whatever {@code successive} also happens to hold (core itself requires exactly one of the two to be
 * non-empty in practice, but this checks the binder's own classification logic is precedence-correct even for
 * combinations core's validation would reject, since {@code strategyOf} has no such guard of its own).
 */
class GreenSchedulerMetricsBinderStrategyPropertiesTest {

    private static final Arbitrary<String> NON_EMPTY_STRING = Arbitrary.of("x", "9:30 11:45", "0H PT1H PT2H");
    private static final Arbitrary<String> POSSIBLY_EMPTY_STRING = Arbitrary.of("", "x", "9:30 11:45", "0H PT1H PT2H");

    @Test
    void fixedWindowAlwaysWinsWhenPresent() {
        Property.def("strategyOf() == fixed_window whenever fixedWindow is non-empty, regardless of successive")
                .forAll(NON_EMPTY_STRING, POSSIBLY_EMPTY_STRING)
                .suchThat((fixedWindow, successive) -> GreenSchedulerMetricsBinder
                        .strategyOf(scheduleWith(fixedWindow, successive)).equals("fixed_window"))
                .check()
                .assertIsSatisfied();
    }

    @Test
    void successiveWinsOverCronWhenFixedWindowAbsent() {
        Property.def("strategyOf() == successive when fixedWindow is empty and successive is non-empty")
                .forAll(NON_EMPTY_STRING)
                .suchThat(successive -> GreenSchedulerMetricsBinder.strategyOf(scheduleWith("", successive))
                        .equals("successive"))
                .check()
                .assertIsSatisfied();
    }

    @Test
    void cronIsTheFallbackWhenNeitherIsSet() {
        var schedule = scheduleWith("", "");

        var strategy = GreenSchedulerMetricsBinder.strategyOf(schedule);

        org.assertj.core.api.Assertions.assertThat(strategy).isEqualTo("cron");
    }

    private static GreenScheduled scheduleWith(String fixedWindow, String successive) {
        GreenScheduled schedule = mock(GreenScheduled.class);
        when(schedule.fixedWindow()).thenReturn(fixedWindow);
        when(schedule.successive()).thenReturn(successive);
        return schedule;
    }
}
