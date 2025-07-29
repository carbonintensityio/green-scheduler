package io.carbonintensity.scheduler.quarkus.test;

import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;

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

    // doesn't work yet, DevUI is not active during QuarkusDevModeTest for some reason
    @Test
    public void testGetData() throws Exception {
        JsonNode data = super.executeJsonRPCMethod("getData", Map.of());
        Assertions.assertTrue(data.get("schedulerRunning").asBoolean());
    }

    static class Jobs {
        @GreenScheduled(identity = "the_schedule", successive = "1S 4S 5S", duration = "PT1M", zone = "NL")
        void ping() {
        }
    }

}
