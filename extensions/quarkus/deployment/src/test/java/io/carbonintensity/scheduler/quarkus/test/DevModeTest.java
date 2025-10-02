package io.carbonintensity.scheduler.quarkus.test;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.carbonintensity.scheduler.GreenScheduled;
import io.quarkus.devui.tests.DevUIJsonRPCTest;
import io.quarkus.test.QuarkusDevModeTest;

public class DevModeTest extends DevUIJsonRPCTest {

    @RegisterExtension
    static final QuarkusDevModeTest test = new QuarkusDevModeTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(DevModeTest.Jobs.class));

    public DevModeTest() {
        super("io.carbonintensity.quarkus-green-scheduler");
    }

    @Test
    public void testGetData() throws Exception {
        JsonNode data = super.executeJsonRPCMethod("getData", Map.of());
        Assertions.assertTrue(data.get("schedulerRunning").asBoolean());
        ArrayNode methods = data.withArray("methods");
        Assertions.assertEquals(2, methods.size());
        Assertions.assertTrue(methods.at("/0/schedules/0/identity").asText().startsWith("the_schedule"));
        Assertions.assertTrue(methods.at("/1/schedules/0/successive").asText().equals("1S 4S 5S"));
    }

    static class Jobs {
        @GreenScheduled(identity = "the_schedule", successive = "1S 4S 5S", duration = "PT1M", carbonIntensityZone = "NL")
        void ping() {
        }

        @GreenScheduled(identity = "the_schedule2", successive = "1S 4S 5S", duration = "PT1M", carbonIntensityZone = "NL")
        void ping2() {
        }
    }

}
