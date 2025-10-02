package io.carbonintensity.scheduler.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.carbonintensity.executionplanner.runtime.impl.CarbonIntensity;
import io.carbonintensity.executionplanner.runtime.impl.ZonedCarbonIntensityPeriod;
import io.carbonintensity.executionplanner.spi.CarbonIntensityApi;
import io.carbonintensity.scheduler.GreenScheduled;
import io.carbonintensity.scheduler.Scheduler;
import io.carbonintensity.scheduler.runtime.impl.rest.CarbonIntensityFileApi;
import io.quarkus.test.QuarkusUnitTest;

public class CustomCarbonIntensityApiTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(CustomCarbonIntensityApiTest.Jobs.class)
                    .addClasses(CustomCarbonIntensityApiTest.CustomCarbonIntensityApi.class)
                    .addAsResource("application.properties"));

    @Inject
    Scheduler greenScheduler;

    @Test
    public void testOptionalCustomApiInjection() {
        assertEquals(1, greenScheduler.getScheduledJobs().size());
    }

    static class Jobs {
        @GreenScheduled(identity = "test", successive = "1S 4S 5S", duration = "PT30M", carbonIntensityZone = "NL")
        void ping() {
        }

        @Produces
        CarbonIntensityApi carbonIntensityApi() {
            return new CustomCarbonIntensityApi();
        }
    }

    static class CustomCarbonIntensityApi implements CarbonIntensityApi {

        @Override
        public CompletableFuture<CarbonIntensity> getCarbonIntensity(ZonedCarbonIntensityPeriod zonedPeriod) {
            return new CarbonIntensityFileApi().getCarbonIntensity(zonedPeriod);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }
    }

}
