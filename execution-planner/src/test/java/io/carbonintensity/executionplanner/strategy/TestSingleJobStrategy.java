package io.carbonintensity.executionplanner.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.planner.Timeslot;
import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.rest.CarbonIntensityJsonParser;

class TestSingleJobStrategy {

    private static final CarbonIntensityJsonParser ciParser = new CarbonIntensityJsonParser();

    @Test
    void testSingleJobStrategy() {

        CarbonIntensity carbonIntensity = loadCarbonIntensityFromFile("day-ahead-20240824-Z.json");
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ws.plusSeconds(60);
        Duration d = Duration.ofSeconds(60);

        SingleJobStrategy initialStrategy = new SingleJobStrategy();
        Timeslot timeslot = initialStrategy.bestTimeslot(ws, we, d, carbonIntensity);

        // check that we have a proper timeslot that fits the bill
        assertThat(timeslot).isNotNull();
        assertThat(Duration.between(timeslot.start(), timeslot.end())).isEqualByComparingTo(d);
        assertThat(timeslot.carbonIntensity()).isLessThan(new BigDecimal("1135"));
    }

    @Test
    void testRankedTimeslotsOrderedAscendingAndMatchesBestTimeslot() {
        CarbonIntensity carbonIntensity = loadCarbonIntensityFromFile("day-ahead-20240824-Z.json");
        ZonedDateTime ws = ZonedDateTime.parse("2024-08-27T00:00:00Z");
        ZonedDateTime we = ws.plusHours(6);
        Duration d = Duration.ofMinutes(30);

        SingleJobStrategy strategy = new SingleJobStrategy();
        List<Timeslot> ranked = strategy.rankedTimeslots(ws, we, d, carbonIntensity);

        assertThat(ranked).isNotEmpty();
        for (int i = 1; i < ranked.size(); i++) {
            assertThat(ranked.get(i - 1).carbonIntensity()).isLessThanOrEqualTo(ranked.get(i).carbonIntensity());
        }
        assertThat(ranked.get(0)).usingRecursiveComparison().isEqualTo(strategy.bestTimeslot(ws, we, d, carbonIntensity));
    }

    private CarbonIntensity loadCarbonIntensityFromFile(String fileName) {
        return ciParser.parse(ClassLoader.getSystemResourceAsStream(fileName));
    }

}
