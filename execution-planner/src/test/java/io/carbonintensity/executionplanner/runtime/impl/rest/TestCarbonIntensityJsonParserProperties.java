package io.carbonintensity.executionplanner.runtime.impl.rest;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.vavr.test.Arbitrary;
import io.vavr.test.Gen;
import io.vavr.test.Property;

/**
 * Property-based tests complementing {@link TestCarbonIntensityJsonParser}: for arbitrary
 * {@link CarbonIntensity} instances, serializing to JSON with {@link CarbonIntensityJsonParser#toJson}
 * and parsing the result back with {@link CarbonIntensityJsonParser#parse} must reproduce the original
 * object (a round-trip property), rather than just the one hand-picked example the example-based test uses.
 *
 * <p>
 * See {@code docs/adr/0001-vavr-test-over-jqwik.md} (in carbonintensity-api) for why vavr-test rather than
 * jqwik is used here.
 */
class TestCarbonIntensityJsonParserProperties {

    private final CarbonIntensityJsonParser parser = new CarbonIntensityJsonParser();

    // Non-null: CarbonIntensityJsonParser#toJson passes the zone straight to JsonObjectBuilder#add(String, String),
    // which throws NullPointerException for a null value - so a null zone is out of scope for a round-trip property.
    private static final Arbitrary<String> ZONES = Arbitrary.of("NL", "BE", "DE", "FR", "GB", "");

    // A plausible range of instants (roughly the years 2001-2033), rather than the full Instant range: the
    // round-trip goes through Instant#toString()/Instant#parse(), which is exact for any instant, so this is
    // about generating realistic data rather than working around a limitation.
    private static final Arbitrary<Instant> INSTANTS = size -> Gen.choose(0L, 2_000_000_000L).map(Instant::ofEpochSecond);

    private static final Arbitrary<Duration> RESOLUTIONS = Arbitrary.of(
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(24));

    // Scale-0 (whole gCO2eq/kWh values), matching how this API actually reports carbon intensity and how the
    // existing example-based test builds its data (BigDecimal.valueOf(1000L)). A non-zero scale would still
    // round-trip correctly through this parser, but risks conflating this property with quirks of the
    // underlying JSON-P BigDecimal (de)serialization rather than the parser's own round-trip behavior.
    private static final Arbitrary<BigDecimal> CARBON_INTENSITY_VALUES = size -> Gen.choose(0, 2_000).map(BigDecimal::valueOf);

    private static final Arbitrary<java.util.List<BigDecimal>> DATA_POINTS = Arbitrary.list(CARBON_INTENSITY_VALUES)
            .map(list -> list.toJavaList());

    @Test
    void parsingTheSerializedJsonReproducesTheOriginalCarbonIntensity() {
        Property.def("parse(toJson(x)) reproduces x")
                .forAll(ZONES, INSTANTS, RESOLUTIONS, DATA_POINTS)
                .suchThat((zone, start, resolution, data) -> {
                    var original = new CarbonIntensity();
                    original.setZone(zone);
                    original.setStart(start);
                    original.setEnd(start.plus(resolution));
                    original.setResolution(resolution);
                    original.setData(data);

                    var json = parser.toJson(original);
                    var roundTripped = parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

                    return roundTripped.getZone().equals(original.getZone())
                            && roundTripped.getStart().equals(original.getStart())
                            && roundTripped.getEnd().equals(original.getEnd())
                            && roundTripped.getResolution().equals(original.getResolution())
                            && roundTripped.getData().equals(original.getData());
                })
                .check()
                .assertIsSatisfied();
    }
}
