package io.carbonintensity.scheduler.runtime.impl.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.planner.fixedwindow.DefaultFixedWindowPlanningConstraints;
import io.carbonintensity.executionplanner.planner.successive.DefaultSuccessivePlanningConstraints;
import io.carbonintensity.executionplanner.spi.PlanningConstraints;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.test.helper.AnnotationUtil;

class GreenScheduledAnnotationParserTest {

    @Test
    void shouldCreateFixedWindowConstraintsWhenFixedWindowIsPresent() {
        GreenScheduled annotation = AnnotationUtil.newGreenScheduled()
                .fixedWindow("9:30 11:45")
                .timeZone("Europe/Amsterdam")
                .duration("15m")
                .carbonIntensityZone("NL")
                .build();

        PlanningConstraints constraints = GreenScheduledAnnotationParser.createConstraints("testJob", annotation,
                Clock.systemDefaultZone());

        assertThat(constraints).isInstanceOf(DefaultFixedWindowPlanningConstraints.class);
        DefaultFixedWindowPlanningConstraints fixedConstraints = (DefaultFixedWindowPlanningConstraints) constraints;
        assertThat(fixedConstraints.getDuration()).isEqualTo(Duration.ofMinutes(15));
        assertThat(fixedConstraints.getCarbonIntensityZone()).isEqualTo("NL");
    }

    @Test
    void shouldCreateSuccessiveConstraintsWhenSuccessiveIsPresent() {
        GreenScheduled annotation = AnnotationUtil.newGreenScheduled()
                .successive("0h 2h 6h")
                .duration("30m")
                .carbonIntensityZone("US")
                .build();
        ZonedDateTime now = ZonedDateTime.now();
        Clock clock = Clock.fixed(now.toInstant(),
                ZoneId.of("UTC"));

        PlanningConstraints constraints = GreenScheduledAnnotationParser.createConstraints("testJob", annotation, clock);

        assertThat(constraints).isInstanceOf(DefaultSuccessivePlanningConstraints.class);
        DefaultSuccessivePlanningConstraints successiveConstraints = (DefaultSuccessivePlanningConstraints) constraints;
        assertThat(successiveConstraints.getInitialMaximumDelay()).isEqualTo(Duration.ZERO);
        assertThat(successiveConstraints.getInitialStartTime()).isEqualTo(now);
        assertThat(successiveConstraints.getMinimumGap()).isEqualTo(Duration.ofHours(2));
        assertThat(successiveConstraints.getMaximumGap()).isEqualTo(Duration.ofHours(6));
        assertThat(successiveConstraints.getDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(successiveConstraints.getCarbonIntensityZone()).isEqualTo("US");
    }

    @Test
    void shouldThrowExceptionWhenNeitherFixedWindowNorSuccessiveIsPresent() {
        GreenScheduled annotation = AnnotationUtil.newGreenScheduled()
                .carbonIntensityZone("NL")
                .build();

        assertThatThrownBy(() -> GreenScheduledAnnotationParser.createConstraints("testJob", annotation, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Found 1 validation error while creating GreenScheduled constraints for testJob: \n" +
                        "Duration must be specified when fixedWindow is specified");
    }

    @Test
    void shouldParseOverdueGracePeriodCorrectly() {
        GreenScheduled annotation = AnnotationUtil.newGreenScheduled()
                .carbonIntensityZone("NL")
                .overdueGracePeriod("PT10M")
                .build();

        Duration gracePeriod = GreenScheduledAnnotationParser.parseOverdueGracePeriod(annotation, Duration.ofMinutes(5));
        assertThat(gracePeriod).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void shouldReturnDefaultGracePeriodWhenNotSet() {
        GreenScheduled annotation = AnnotationUtil.newGreenScheduled()
                .carbonIntensityZone("NL")
                .build();

        Duration gracePeriod = GreenScheduledAnnotationParser.parseOverdueGracePeriod(annotation, Duration.ofMinutes(5));
        assertThat(gracePeriod).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void shouldThrowExceptionForInvalidOverdueGracePeriod() {
        GreenScheduled annotation = AnnotationUtil.newGreenScheduled()
                .overdueGracePeriod("invalid")
                .carbonIntensityZone("NL")
                .build();
        Duration duration = Duration.ofMinutes(5);

        assertThatThrownBy(() -> GreenScheduledAnnotationParser.parseOverdueGracePeriod(annotation, duration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ISO 8601 duration format");
    }
}
